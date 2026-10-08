#!/usr/bin/env bash
# CloudSim experiment (Fase 6): KPB k=100% vs HS vs CSA on each dataset, HS/CSA over several seeds.
# Every run goes into one new session folder; nothing outside it is written.
#
#   bash scripts/run_cloudsim_experiment.sh
#   DATASETS="100 500" SEEDS="1 2" EVALS=2000 bash scripts/run_cloudsim_experiment.sh
set -euo pipefail
cd "$(dirname "$0")/.."

DATASETS=${DATASETS:-"100 500 1000"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
EVALS=${EVALS:-10000}
SESSION=${SESSION:-results/sessions/cloudsim_$(date +%Y%m%d_%H%M%S)}

if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
mkdir -p "$SESSION"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"

run() { # tag, then Experiment arguments
  local tag=$1; shift
  echo "== $tag"
  java -cp "$CP" -Dlog=error project.Experiment --out "$SESSION" --evals "$EVALS" "$@" \
    > "$SESSION/log_$tag.txt"
  grep -E "^(makespan|algo)=" "$SESSION/log_$tag.txt" | sed 's/^/   /'
}

for n in $DATASETS; do
  dataset=src/main/resources/dataset/GoCJ_Dataset_$n.txt
  run "GoCJ_${n}_kpb_k100" --dataset "$dataset" --algo kpb --k 100
  for s in $SEEDS; do
    run "GoCJ_${n}_hs_seed$s" --dataset "$dataset" --algo hs --seed "$s"
    run "GoCJ_${n}_csa_seed$s" --dataset "$dataset" --algo csa --seed "$s"
  done
done

python3 scripts/summarize_session.py "$SESSION"
