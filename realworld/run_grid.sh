#!/usr/bin/env bash
# Real-world KPB grid for the demo feedback: dataset sizes x k x REPS on the Docker containers, run order
# shuffled, pause between runs, scale and itersPerMI locked for the whole session, docker stats recorded.
# KPB mappings are exported from Java up front (same code as CloudSim), so nothing compiles mid-grid.
# Logs are log_n<N>_k<K>_r<R>.txt, read by analyze_grid.py.
#
#   bash run_grid.sh                      SOURCE=gocj: GoCJ 50 (subset), 100..1000, 3 repetitions, itersPerMI 14
#   SOURCE=synthetic bash run_grid.sh     Synthetic 1000, 5000, 10000, 1 repetition, itersPerMI 7
#   SCENARIOS="100 200" REPS=1 bash run_grid.sh
set -euo pipefail
cd "$(dirname "$0")"
REALWORLD=$PWD
REPO=$(dirname "$REALWORLD")
source "$REPO/scripts/power_state.sh"

SOURCE=${SOURCE:-gocj}
case $SOURCE in
  gocj)
    SCENARIOS=${SCENARIOS:-"50 100 200 300 400 500 600 700 800 900 1000"}
    REPS=${REPS:-3}
    ITERS_PER_MI=${ITERS_PER_MI:-14} ;;
  synthetic)
    # Full synthetic grid would take ~12-15 h; 1000/5000/10000 with 1 repetition at half the load ~35 min.
    SCENARIOS=${SCENARIOS:-"1000 5000 10000"}
    REPS=${REPS:-1}
    ITERS_PER_MI=${ITERS_PER_MI:-7} ;;
  *) echo "[ERROR] SOURCE must be gocj or synthetic" >&2; exit 1 ;;
esac
KS=${KS:-"20 50 100"}
PAUSE=${PAUSE:-3}
ORDER_SEED=${ORDER_SEED:-2026}
SESSION=${SESSION:-$REALWORLD/results/sessions/${SOURCE}_$(date +%Y%m%d_%H%M%S)}

dataset_file() {
  case $SOURCE in
    gocj) if [ "$1" = 50 ]; then echo GoCJ_Dataset_50_subset.txt; else echo "GoCJ_Dataset_$1.txt"; fi ;;
    synthetic) echo "Synthetic_Dataset_$1.txt" ;;
  esac
}

if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
require_stable_power
mkdir -p "$SESSION/mappings"

echo "== 1/4 export mapping KPB dari Java"
(cd "$REPO" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="$REPO/target/classes:$(cat "$REPO/target/classpath.txt")"
for n in $SCENARIOS; do
  for k in $KS; do
    java -cp "$CP" -Dlog=error project.Experiment --dataset "$REPO/src/main/resources/dataset/$(dataset_file "$n")" \
      --k "$k" --no-sim --export-mapping "$SESSION/mappings/n${n}_k$k.json" > /dev/null
  done
done

echo "== 2/4 kalibrasi scale (median dari 5, itersPerMI=$ITERS_PER_MI)"
SCALE=${SCALE:-$(for i in 1 2 3 4 5; do
  python3 kpb_realworld.py --calibrate-only --iters-per-mi "$ITERS_PER_MI" | sed -E 's/^scale=([0-9.]+).*/\1/'
done | sort -g | sed -n 3p)}
cat > "$SESSION/parameters.txt" <<EOF
source=$SOURCE
scenarios=$SCENARIOS
ks=$KS
reps=$REPS
scale=$SCALE (locked)
itersPerMI=$ITERS_PER_MI (locked)
pause_s=$PAUSE
order_seed=$ORDER_SEED
other_containers=$(docker ps --format "{{.Names}}" | grep -v kpb_vm | tr "\n" " ")
started=$(date -Iseconds)
power_start=$(power_state)
EOF
sed 's/^/   /' "$SESSION/parameters.txt"

for n in $SCENARIOS; do for k in $KS; do for r in $(seq 1 "$REPS"); do echo "$n $k $r"; done; done; done \
  | python3 -c "import random, sys; runs = sys.stdin.read().split('\n')[:-1]; random.Random($ORDER_SEED).shuffle(runs); print('\n'.join(runs))" \
  > "$SESSION/run_order.txt"
TOTAL=$(wc -l < "$SESSION/run_order.txt")

echo "== 3/4 $TOTAL run (urutan acak), docker stats direkam"
echo "epoch_s,container,cpu_pct,mem_usage" > "$SESSION/docker_stats.csv"
WORKERS=$(docker ps -q --filter name=kpb_vm)
( while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $WORKERS \
      | sed "s/^/$(date +%s.%N),/" >> "$SESSION/docker_stats.csv"
    sleep 3
  done ) &
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

i=0
while read -r n k r <&3; do
  i=$((i + 1))
  log="$SESSION/log_n${n}_k${k}_r$r.txt"
  python3 kpb_realworld.py --dataset "$REPO/src/main/resources/dataset/$(dataset_file "$n")" \
    --mapping "$SESSION/mappings/n${n}_k$k.json" --repeat 1 --first-run "$r" \
    --scale "$SCALE" --iters-per-mi "$ITERS_PER_MI" --quiet --out "$SESSION" > "$log"
  printf '[%3d/%d] n=%-6s k=%-4s r%s %s | %s\n' "$i" "$TOTAL" "$n" "$k" "$r" \
    "$(grep -oE 'makespan=[0-9.]+s \(nyata/prediksi=[0-9.]+x\)' "$log")" "$(power_state < /dev/null | cut -d' ' -f1,2,4)"
  sleep "$PAUSE"
done 3< "$SESSION/run_order.txt"
kill $STATS_PID 2>/dev/null || true
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"
echo "power_end=$(power_state)" >> "$SESSION/parameters.txt"

echo "== 4/4 rekap"
python3 analyze_grid.py "$SESSION"
