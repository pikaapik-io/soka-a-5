#!/usr/bin/env bash
# Real-world experiment (Fase 6b): the CloudSim grid (KPB k=100%, HS and CSA over seeds) on the Docker
# containers, REPS repetitions per cell, cells in shuffled order, scale and itersPerMI locked for every
# run, docker stats recorded. Mappings are exported from Java up front so no compilation runs mid-grid.
#
#   bash scripts/run_realworld_experiment.sh
#   DATASETS="100" SEEDS="1" REPS=1 bash scripts/run_realworld_experiment.sh     (quick check)
#
# Re-running part of an earlier session (same mappings, same locked scale, given run order):
#   MAPPINGS_DIR=<old>/mappings SCALE=<old scale> RUNS_FILE=<file with "n cell rep" lines> bash ...
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD

DATASETS=${DATASETS:-"100 500 1000"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
EVALS=${EVALS:-10000}
REPS=${REPS:-3}
ITERS_PER_MI=${ITERS_PER_MI:-14}
PAUSE=${PAUSE:-3}
ORDER_SEED=${ORDER_SEED:-2026}
SESSION=${SESSION:-$REPO/realworld/results/sessions/experiment_$(date +%Y%m%d_%H%M%S)}

power_state() { # Windows power mode, plan and AC status, when running under WSL
  command -v powershell.exe >/dev/null || { echo "n/a"; return; }
  local overlay plan battery
  overlay=$(powershell.exe -NoProfile -Command '(Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes").ActiveOverlayAcPowerScheme' 2>/dev/null | tr -d '\r')
  plan=$(powercfg.exe /getactivescheme 2>/dev/null | tr -d '\r' | sed -E 's/.*\((.*)\).*/\1/')
  battery=$(powershell.exe -NoProfile -Command '$b = Get-CimInstance Win32_Battery; "$($b.BatteryStatus)/$($b.EstimatedChargeRemaining)%"' 2>/dev/null | tr -d '\r')
  case $overlay in
    961cc777-2547-4f9d-8174-7d86181b8a7a) overlay="Better battery" ;;
    3af9b8d9-7c97-431d-ad78-34a8bfea439f) overlay="Better performance" ;;
    ded574b5-45a0-4f42-8737-46345c09c238) overlay="Best performance" ;;
    ""|00000000-0000-0000-0000-000000000000) overlay="Balanced" ;;
  esac
  echo "power_mode=$overlay plan=$plan battery_status/charge=$battery (status 2 = AC)"
}

if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
# Per-core speed follows the Windows power mode; a slower mode stretched runs by ~50% in earlier sessions.
POWER=$(power_state)
if [[ $POWER != n/a && $POWER != *"power_mode=Best performance"* && -z ${FORCE:-} ]]; then
  echo "[ERROR] $POWER" >&2
  echo "        Set Windows power mode to 'Best performance' on AC power, or run with FORCE=1" >&2
  exit 1
fi
mkdir -p "$SESSION/mappings" "$SESSION/logs"

CELLS=()
for n in $DATASETS; do
  for cell in kpb_k100 $(for s in $SEEDS; do echo "hs_seed$s csa_seed$s"; done); do
    CELLS+=("$n $cell")
  done
done

if [ -n "${MAPPINGS_DIR:-}" ]; then
  echo "== 1/4 mapping disalin dari $MAPPINGS_DIR"
  for c in "${CELLS[@]}"; do
    read -r n cell <<< "$c"
    cp "$MAPPINGS_DIR/GoCJ_${n}_$cell.json" "$SESSION/mappings/"
  done
else
  echo "== 1/4 export mapping dari Java"
  mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
  CP="target/classes:$(cat target/classpath.txt)"
  for c in "${CELLS[@]}"; do
    read -r n cell <<< "$c"
    case $cell in
      kpb_k100) algo_args=(--algo kpb --k 100) ;;
      hs_seed*) algo_args=(--algo hs --seed "${cell#hs_seed}" --evals "$EVALS") ;;
      csa_seed*) algo_args=(--algo csa --seed "${cell#csa_seed}" --evals "$EVALS") ;;
    esac
    java -cp "$CP" -Dlog=error project.Experiment --dataset "src/main/resources/dataset/GoCJ_Dataset_$n.txt" \
      "${algo_args[@]}" --no-sim --export-mapping "$SESSION/mappings/GoCJ_${n}_$cell.json" >> "$SESSION/logs/export.txt"
  done
fi
echo "   $(ls "$SESSION/mappings" | wc -l) mapping"

cd realworld
if [ -n "${SCALE:-}" ]; then
  echo "== 2/4 scale diberikan: $SCALE"
  SCALE_NOTE="locked, given"
else
  echo "== 2/4 kalibrasi scale (median dari 5)"
  SCALE=$(for i in 1 2 3 4 5; do
    python3 kpb_realworld.py --calibrate-only --iters-per-mi "$ITERS_PER_MI" | sed -E 's/^scale=([0-9.]+).*/\1/'
  done | sort -g | sed -n 3p)
  SCALE_NOTE="locked, median of 5 calibrations"
fi
cat > "$SESSION/parameters.txt" <<EOF
scale=$SCALE ($SCALE_NOTE)
mappings_dir=${MAPPINGS_DIR:-exported}
runs_file=${RUNS_FILE:-shuffled grid}
itersPerMI=$ITERS_PER_MI (locked)
datasets=$DATASETS
seeds=$SEEDS
evals=$EVALS
reps=$REPS
pause_s=$PAUSE
stats_interval_s=${STATS_INTERVAL:-3}
other_containers=$(docker ps --format "{{.Names}}" | grep -v kpb_vm | tr "\n" " ")
order_seed=$ORDER_SEED
started=$(date -Iseconds)
power_start=$(power_state)
EOF
cat "$SESSION/parameters.txt" | sed 's/^/   /'

if [ -n "${RUNS_FILE:-}" ]; then
  cp "$RUNS_FILE" "$SESSION/run_order.txt"
else
printf '%s\n' "${CELLS[@]}" | python3 -c "
import random, sys
cells = [line.split() for line in sys.stdin if line.strip()]
runs = [(n, cell, rep) for n, cell in cells for rep in range(1, $REPS + 1)]
random.Random($ORDER_SEED).shuffle(runs)
print('\n'.join(f'{n} {cell} {rep}' for n, cell, rep in runs))" > "$SESSION/run_order.txt"
fi
TOTAL=$(wc -l < "$SESSION/run_order.txt")
if [ "$TOTAL" -eq 0 ] || grep -qvE '^[0-9]+ [a-z0-9_]+ [0-9]+$' "$SESSION/run_order.txt"; then
  echo "[ERROR] run_order.txt is empty or malformed" >&2
  exit 1
fi

echo "== 3/4 $TOTAL run (urutan acak), docker stats direkam"
echo "epoch_s,container,cpu_pct,mem_usage" > "$SESSION/docker_stats.csv"
WORKERS=$(docker ps -q --filter name=kpb_vm)
( while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $WORKERS \
      | sed "s/^/$(date +%s.%N),/" >> "$SESSION/docker_stats.csv"
    sleep "${STATS_INTERVAL:-3}"
  done ) &
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

i=0
while read -r n cell rep; do
  i=$((i + 1))
  log="$SESSION/logs/log_GoCJ_${n}_${cell}_r$rep.txt"
  python3 kpb_realworld.py --dataset "../src/main/resources/dataset/GoCJ_Dataset_$n.txt" \
    --mapping "$SESSION/mappings/GoCJ_${n}_$cell.json" --repeat 1 --first-run "$rep" \
    --scale "$SCALE" --iters-per-mi "$ITERS_PER_MI" --quiet --out "$SESSION" > "$log"
  printf '[%3d/%d] GoCJ %-4s %-10s r%s %s\n' "$i" "$TOTAL" "$n" "$cell" "$rep" \
    "$(grep -oE 'makespan=[0-9.]+s \(nyata/prediksi=[0-9.]+x\)' "$log")"
  sleep "$PAUSE"
done < "$SESSION/run_order.txt"
kill $STATS_PID 2>/dev/null || true
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"
echo "power_end=$(power_state)" >> "$SESSION/parameters.txt"

echo "== 4/4 rekap dan validasi"
cd "$REPO"
python3 scripts/summarize_session.py "$SESSION"
python3 scripts/validate_realworld_session.py "$SESSION"
