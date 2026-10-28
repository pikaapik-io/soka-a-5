#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
REALWORLD=$PWD
REPO=$(dirname "$REALWORLD")
DATASET_DIR="$REPO/src/main/resources/dataset"

GOCJ_SCENARIOS=${GOCJ_SCENARIOS:-"100 200 300 400 500 600 700 800 900 1000"}
SYNTHETIC_SCENARIOS=${SYNTHETIC_SCENARIOS:-"1000 2000 3000 4000 5000 6000 7000 8000 9000 10000"}
KS=${KS:-"20 50 100"}
REPS=${REPS:-1}
ITERS_GOCJ=${ITERS_GOCJ:-14}
ITERS_SYNTHETIC=${ITERS_SYNTHETIC:-7}
PAUSE=${PAUSE:-2}
SESSION=${SESSION:-"$REALWORLD/results/sessions/all_$(date +%Y%m%d_%H%M%S)"}

if [[ -e "$SESSION" ]]; then
  echo "[ERROR] $SESSION already exists; choose a new SESSION directory" >&2
  exit 1
fi
if ! [[ "$REPS" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ERROR] REPS must be a positive integer" >&2
  exit 1
fi

for n in $GOCJ_SCENARIOS; do
  [[ -f "$DATASET_DIR/GoCJ_Dataset_${n}.txt" ]] || {
    echo "[ERROR] Missing dataset: $DATASET_DIR/GoCJ_Dataset_${n}.txt" >&2
    exit 1
  }
done
for n in $SYNTHETIC_SCENARIOS; do
  [[ -f "$DATASET_DIR/Synthetic_Dataset_${n}.txt" ]] || {
    echo "[ERROR] Missing dataset: $DATASET_DIR/Synthetic_Dataset_${n}.txt" >&2
    exit 1
  }
done

mkdir -p "$SESSION/mappings" "$SESSION/logs/gocj" "$SESSION/logs/synthetic"
cat > "$SESSION/parameters.txt" <<EOF
gocj_scenarios=$GOCJ_SCENARIOS
synthetic_scenarios=$SYNTHETIC_SCENARIOS
ks=$KS
repetitions=$REPS
iters_per_mi_gocj=$ITERS_GOCJ
iters_per_mi_synthetic=$ITERS_SYNTHETIC
pause_s=$PAUSE
started=$(date -Iseconds)
EOF

echo "Memeriksa worker Kubernetes/Docker pada port 9100-9119..."
for port in $(seq 9100 9119); do
  if ! curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:${port}/health" >/dev/null; then
    echo "[ERROR] Worker tidak sehat pada port $port; pastikan semua 20 Pod/worker Ready." >&2
    exit 1
  fi
done

echo "Mempersiapkan classpath Java..."
(cd "$REPO" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="$REPO/target/classes:$(cat "$REPO/target/classpath.txt")"

export_mappings() {
  local source=$1
  local scenarios=$2
  local dataset_prefix=$3
  echo "Mengekspor mapping KPB: $source"
  for n in $scenarios; do
    for k in $KS; do
      java -cp "$CP" -Dlog=error project.Experiment \
        --dataset "$DATASET_DIR/${dataset_prefix}_${n}.txt" \
        --k "$k" --no-sim \
        --export-mapping "$SESSION/mappings/${source}_n${n}_k${k}.json" >/dev/null
    done
  done
}

echo "Kalibrasi beban real-world..."
SCALE_GOCJ=$(python3 kpb_realworld.py --calibrate-only --iters-per-mi "$ITERS_GOCJ" |
  sed -n 's/^scale=\([0-9.]*\).*/\1/p')
SCALE_SYNTHETIC=$(python3 kpb_realworld.py --calibrate-only --iters-per-mi "$ITERS_SYNTHETIC" |
  sed -n 's/^scale=\([0-9.]*\).*/\1/p')
if [[ -z "$SCALE_GOCJ" || -z "$SCALE_SYNTHETIC" ]]; then
  echo "[ERROR] Calibration did not return a scale value" >&2
  exit 1
fi
printf 'scale_gocj=%s\nscale_synthetic=%s\n' "$SCALE_GOCJ" "$SCALE_SYNTHETIC" \
  | tee -a "$SESSION/parameters.txt"

export_mappings gocj "$GOCJ_SCENARIOS" GoCJ_Dataset
export_mappings synthetic "$SYNTHETIC_SCENARIOS" Synthetic_Dataset

TOTAL=$(( $(wc -w <<< "$GOCJ_SCENARIOS") + $(wc -w <<< "$SYNTHETIC_SCENARIOS") ))
TOTAL=$(( TOTAL * $(wc -w <<< "$KS") * REPS ))
echo "Menjalankan $TOTAL skenario; hasil disimpan di $SESSION"

run_source() {
  local source=$1
  local scenarios=$2
  local dataset_prefix=$3
  local iters=$4
  local scale=$5

  for n in $scenarios; do
    for k in $KS; do
      local mapping="$SESSION/mappings/${source}_n${n}_k${k}.json"
      for r in $(seq 1 "$REPS"); do
        local log="$SESSION/logs/$source/log_n${n}_k${k}_r${r}.txt"
        if ! python3 kpb_realworld.py \
          --dataset "$DATASET_DIR/${dataset_prefix}_${n}.txt" \
          --mapping "$mapping" --repeat 1 --first-run "$r" \
          --scale "$scale" --iters-per-mi "$iters" --quiet --out "$SESSION" \
          >"$log" 2>&1; then
          cat "$log"
          echo "[ERROR] Gagal: $source n=$n k=$k r=$r; log: $log" >&2
          exit 1
        fi
        printf '[%s] %s n=%s k=%s r=%s: ' "$(date +%H:%M:%S)" "$source" "$n" "$k" "$r"
        grep -E 'makespan=.*vmsUsed=' "$log" || true
        sleep "$PAUSE"
      done
    done
  done
}

run_source gocj "$GOCJ_SCENARIOS" GoCJ_Dataset "$ITERS_GOCJ" "$SCALE_GOCJ"
run_source synthetic "$SYNTHETIC_SCENARIOS" Synthetic_Dataset "$ITERS_SYNTHETIC" "$SCALE_SYNTHETIC"

echo "Menganalisis hasil..."
python3 analyze_grid.py "$SESSION/logs/gocj"
python3 analyze_grid.py "$SESSION/logs/synthetic"
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"
echo "Selesai. Ringkasan gabungan: $SESSION/summary.csv"
echo "Ringkasan per sumber: $SESSION/logs/gocj/grid_summary.csv dan $SESSION/logs/synthetic/grid_summary.csv"
