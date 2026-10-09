#!/usr/bin/env bash
# CloudSim KPB grid for the demo feedback: every dataset size x k, REPS runs per cell (CloudSim is
# deterministic, so 1 is enough). Each run's log is log_n<N>_k<K>_r<R>.txt in a new session folder,
# in the same metric-line format as the real-world runner, so realworld/analyze_grid.py reads both.
#
#   SOURCE=gocj bash scripts/run_grid_cloudsim.sh        GoCJ 50 (subset), 100..1000
#   SOURCE=synthetic bash scripts/run_grid_cloudsim.sh   Synthetic 1000..10000
set -euo pipefail
cd "$(dirname "$0")/.."

SOURCE=${SOURCE:-gocj}
KS=${KS:-"20 50 100"}
REPS=${REPS:-1}
case $SOURCE in
  gocj) SIZES=${SIZES:-"50 100 200 300 400 500 600 700 800 900 1000"} ;;
  synthetic) SIZES=${SIZES:-"1000 2000 3000 4000 5000 6000 7000 8000 9000 10000"} ;;
  *) echo "[ERROR] SOURCE must be gocj or synthetic" >&2; exit 1 ;;
esac
SESSION=${SESSION:-results/sessions/cloudsim_${SOURCE}_$(date +%Y%m%d_%H%M%S)}
if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
mkdir -p "$SESSION"

dataset_file() {
  case $SOURCE in
    gocj) if [ "$1" = 50 ]; then echo GoCJ_Dataset_50_subset.txt; else echo "GoCJ_Dataset_$1.txt"; fi ;;
    synthetic) echo "Synthetic_Dataset_$1.txt" ;;
  esac
}

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"
printf 'source=%s\nsizes=%s\nks=%s\nreps=%s\nstarted=%s\n' "$SOURCE" "$SIZES" "$KS" "$REPS" "$(date -Iseconds)" \
  > "$SESSION/parameters.txt"

for n in $SIZES; do
  dataset=src/main/resources/dataset/$(dataset_file "$n")
  for k in $KS; do
    for r in $(seq 1 "$REPS"); do
      log="$SESSION/log_n${n}_k${k}_r$r.txt"
      start=$(date +%s.%N)
      java -cp "$CP" -Dlog=error project.Experiment --dataset "$dataset" --algo kpb --k "$k" --out "$SESSION" > "$log"
      printf 'n=%-6s k=%-4s r=%s %6.1f s wall | %s\n' "$n" "$k" "$r" "$(echo "$(date +%s.%N) - $start" | bc)" \
        "$(grep -oE 'makespan=[0-9.]+s \(nyata/prediksi=[0-9.]+x\)' "$log")"
    done
  done
done
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"

python3 realworld/analyze_grid.py "$SESSION"
