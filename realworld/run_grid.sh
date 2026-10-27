#!/usr/bin/env bash
# Real-world KPB grid for the demo feedback: dataset sizes x k x REPS on the Docker containers, run order
# shuffled, pause between runs, scale and itersPerMI locked for the whole session, docker stats recorded.
# KPB mappings are exported from Java up front (same code as CloudSim), so nothing compiles mid-grid.
# Logs are log_n<N>_k<K>_r<R>.txt, read by analyze_grid.py.
#
#   bash run_grid.sh                      SOURCE=gocj: GoCJ 50 (subset), 100..1000, 3 repetitions, itersPerMI 14
#   SOURCE=synthetic bash run_grid.sh     Synthetic 1000, 5000, 10000, 1 repetition, itersPerMI 7
#   SCENARIOS="100 200" REPS=1 bash run_grid.sh
#
# ============================================================================================
# run_grid.sh - grid eksperimen KPB REAL-WORLD di 20 container Docker
# ============================================================================================
# Perbedaan dengan scripts/run_grid_cloudsim.sh:
#   Di sini task benar-benar dieksekusi (CPU dibakar) di 20 container (docker-compose.yml), jadi
#   hasilnya dipengaruhi kondisi mesin. Karena itu skrip ini menambah langkah-langkah kontrol:
#     * cek mode daya Windows sebelum mulai (scripts/power_state.sh)
#     * repetisi (REPS=3) untuk mengukur variasi (std, CV)
#     * urutan run diacak (dengan seed tetap) supaya efek "mesin makin panas/lambat" tidak selalu
#       mengenai sel (n, k) yang sama
#     * jeda PAUSE detik antar-run
#     * scale dan itersPerMI dikunci untuk satu sesi penuh (tidak dikalibrasi ulang per run)
#     * docker stats direkam di background untuk membuktikan batas CPU container benar-benar berlaku
#
# Istilah:
#   itersPerMI  jumlah iterasi loop CPU (worker.py: burn) per 1 MI panjang task. Task L MI dikirim
#               sebagai L x itersPerMI iterasi. Synthetic memakai 7 (setengah beban) karena task-nya jauh
#               lebih banyak.
#   scale       faktor konversi: 1 detik simulasi (rumus L/C) = scale detik nyata di mesin ini.
#               Dipakai untuk "prediksi" makespan nyata = makespan analitik x scale.
#
# Prasyarat: container sudah jalan (cd realworld && docker compose up -d), Java + Maven, python3.
#
# Input (environment variable, semua opsional): SOURCE, SCENARIOS (ukuran dataset), REPS, ITERS_PER_MI,
#   KS, PAUSE (detik), ORDER_SEED (seed pengacakan urutan), SESSION (folder output), SCALE (lewati
#   kalibrasi), FORCE=1 (lewati cek daya, dibaca power_state.sh).
#
# Output di $SESSION (default realworld/results/sessions/<SOURCE>_<timestamp>):
#   mappings/n<N>_k<K>.json     mapping KPB hasil Java untuk tiap sel
#   parameters.txt              semua parameter + kondisi daya awal/akhir + container lain yang jalan
#   run_order.txt               urutan run setelah diacak (satu baris "n k r")
#   docker_stats.csv            sampel CPU%/memori tiap container setiap ±3 detik
#   log_n<N>_k<K>_r<R>.txt      stdout kpb_realworld.py per run (berisi baris metrik)
#   summary.csv, tasks_*.csv    ditulis kpb_realworld.py (--out)
#   grid_summary.csv            rekap dari analyze_grid.py
#
# Alur 4 tahap (lihat tanda "== 1/4" s.d. "== 4/4" di bawah):
#   1/4 export mapping KPB dari Java untuk semua (n, k)
#   2/4 kalibrasi scale (median 5 kali), tulis parameters.txt, acak urutan run
#   3/4 eksekusi semua run berurutan sesuai run_order.txt sambil merekam docker stats
#   4/4 rekap dengan analyze_grid.py
# ============================================================================================
set -euo pipefail
# Pindah ke folder realworld/ (lokasi skrip ini); REALWORLD = path absolutnya, REPO = root repo.
cd "$(dirname "$0")"
REALWORLD=$PWD
REPO=$(dirname "$REALWORLD")
# Muat fungsi power_state dan require_stable_power.
source "$REPO/scripts/power_state.sh"

# Parameter default per SOURCE (bisa ditimpa lewat environment variable).
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
# Jeda antar-run (detik) supaya container/CPU kembali idle sebelum run berikutnya.
PAUSE=${PAUSE:-3}
# Seed tetap -> urutan acak yang sama bila sesi diulang (reproducible).
ORDER_SEED=${ORDER_SEED:-2026}
SESSION=${SESSION:-$REALWORLD/results/sessions/${SOURCE}_$(date +%Y%m%d_%H%M%S)}

# dataset_file N: ukuran -> nama file dataset (sama dengan scripts/run_grid_cloudsim.sh).
dataset_file() {
  case $SOURCE in
    gocj) if [ "$1" = 50 ]; then echo GoCJ_Dataset_50_subset.txt; else echo "GoCJ_Dataset_$1.txt"; fi ;;
    synthetic) echo "Synthetic_Dataset_$1.txt" ;;
  esac
}

# Folder sesi tidak pernah dipakai ulang, supaya data sesi lama tidak tercampur.
if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
# Berhenti di sini bila laptop tidak di AC + "Best performance" (kecuali FORCE=1).
require_stable_power
mkdir -p "$SESSION/mappings"

# ---- Tahap 1/4: mapping KPB dihitung oleh kode Java yang SAMA dengan simulasi CloudSim. ----
# Semua mapping dibuat di awal, sehingga selama eksekusi tidak ada compile/JVM yang ikut memakai CPU.
echo "== 1/4 export mapping KPB dari Java"
(cd "$REPO" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="$REPO/target/classes:$(cat "$REPO/target/classpath.txt")"
for n in $SCENARIOS; do
  for k in $KS; do
    # --no-sim: hanya jalankan KPB (tanpa simulasi CloudSim); --export-mapping: tulis JSON berisi
    # mapping task->VM, kapasitas VM, makespan analitik, dan waktu penjadwalan. stdout dibuang.
    java -cp "$CP" -Dlog=error project.Experiment --dataset "$REPO/src/main/resources/dataset/$(dataset_file "$n")" \
      --k "$k" --no-sim --export-mapping "$SESSION/mappings/n${n}_k$k.json" > /dev/null
  done
done

# ---- Tahap 2/4: kalibrasi scale. ----
# kpb_realworld.py --calibrate-only mengirim satu task uji ke vm00 (V3, 1,0 CPU), mengukur iterasi/detik,
# lalu mencetak "scale=<angka> itersPerMI=<angka>". sed -E mengambil angkanya saja. Diulang 5 kali,
# diurutkan numerik (sort -g), lalu diambil baris ke-3 (sed -n 3p) = MEDIAN dari 5, supaya tahan
# terhadap satu pengukuran yang kebetulan lambat/cepat. Bila SCALE sudah diset dari luar, kalibrasi dilewati.
echo "== 2/4 kalibrasi scale (median dari 5, itersPerMI=$ITERS_PER_MI)"
SCALE=${SCALE:-$(for i in 1 2 3 4 5; do
  python3 kpb_realworld.py --calibrate-only --iters-per-mi "$ITERS_PER_MI" | sed -E 's/^scale=([0-9.]+).*/\1/'
done | sort -g | sed -n 3p)}
# Catat semua parameter sesi. other_containers = container lain (bukan kpb_vm*) yang sedang jalan,
# karena mereka bisa ikut memakai CPU dan mengganggu pengukuran. power_start = kondisi daya awal.
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
# Tampilkan parameters.txt di terminal dengan indentasi 3 spasi.
sed 's/^/   /' "$SESSION/parameters.txt"

# Susun daftar semua run "n k r" (triple loop), lalu acak urutannya dengan Python random.Random(seed).shuffle.
# split('\n')[:-1] membuang elemen kosong setelah newline terakhir. Hasilnya disimpan di run_order.txt.
for n in $SCENARIOS; do for k in $KS; do for r in $(seq 1 "$REPS"); do echo "$n $k $r"; done; done; done \
  | python3 -c "import random, sys; runs = sys.stdin.read().split('\n')[:-1]; random.Random($ORDER_SEED).shuffle(runs); print('\n'.join(runs))" \
  > "$SESSION/run_order.txt"
TOTAL=$(wc -l < "$SESSION/run_order.txt")

# ---- Tahap 3/4: eksekusi semua run. ----
echo "== 3/4 $TOTAL run (urutan acak), docker stats direkam"
echo "epoch_s,container,cpu_pct,mem_usage" > "$SESSION/docker_stats.csv"
# ID semua container worker (nama mengandung kpb_vm).
WORKERS=$(docker ps -q --filter name=kpb_vm)
# Perekam docker stats di background (subshell + &): setiap ±3 detik ambil satu snapshot
# (--no-stream) CPU% dan memori tiap worker, dalam format "nama,CPU%,memori". sed menambahkan epoch
# saat ini di depan setiap baris -> CSV epoch_s,container,cpu_pct,mem_usage. Dipakai analyze_grid.py
# untuk membandingkan CPU% sibuk per tier dengan limit container (V3 100%, V2 30%, V1 10%).
( while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $WORKERS \
      | sed "s/^/$(date +%s.%N),/" >> "$SESSION/docker_stats.csv"
    sleep 3
  done ) &
# $! = PID proses background terakhir. trap EXIT memastikan perekam dimatikan bila skrip selesai
# ATAU berhenti karena error / Ctrl+C, supaya tidak ada loop yatim yang terus berjalan.
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

i=0
# Baca run_order.txt lewat file descriptor 3 (lihat "3<" di akhir loop), bukan stdin. Dengan begitu
# perintah di dalam loop (python3, docker, powershell.exe) tidak ikut "memakan" isi run_order dari stdin.
while read -r n k r <&3; do
  i=$((i + 1))
  log="$SESSION/log_n${n}_k${k}_r$r.txt"
  # Satu run: eksekusi mapping yang sudah diekspor (--mapping) di 20 container. --repeat 1 --first-run r
  # membuat hasil diberi nomor run r (tasks_..._run<r>.csv) sehingga repetisi tidak saling timpa.
  # --scale dan --iters-per-mi memakai nilai yang dikunci di tahap 2. --quiet mematikan log per cloudlet.
  python3 kpb_realworld.py --dataset "$REPO/src/main/resources/dataset/$(dataset_file "$n")" \
    --mapping "$SESSION/mappings/n${n}_k$k.json" --repeat 1 --first-run "$r" \
    --scale "$SCALE" --iters-per-mi "$ITERS_PER_MI" --quiet --out "$SESSION" > "$log"
  # Progres satu baris: [i/TOTAL] n k r | makespan + rasio nyata/prediksi | kondisi daya saat ini.
  # power_state < /dev/null: stdin powershell.exe diputus agar tidak membaca input terminal.
  # cut -d' ' -f1,2,4 mengambil kata ke-1,2 ("power_mode=Best performance") dan ke-4 (status baterai,
  # dengan asumsi nama plan satu kata) dari output power_state.
  printf '[%3d/%d] n=%-6s k=%-4s r%s %s | %s\n' "$i" "$TOTAL" "$n" "$k" "$r" \
    "$(grep -oE 'makespan=[0-9.]+s \(nyata/prediksi=[0-9.]+x\)' "$log")" "$(power_state < /dev/null | cut -d' ' -f1,2,4)"
  sleep "$PAUSE"
done 3< "$SESSION/run_order.txt"
# Selesai: hentikan perekam docker stats, catat waktu selesai dan kondisi daya akhir
# (bandingkan dengan power_start untuk memastikan mode daya tidak berubah selama sesi).
kill $STATS_PID 2>/dev/null || true
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"
echo "power_end=$(power_state)" >> "$SESSION/parameters.txt"

# ---- Tahap 4/4: rekap mean ± std per (n, k), rasio nyata/prediksi, CV, drift, CPU% per tier. ----
echo "== 4/4 rekap"
python3 analyze_grid.py "$SESSION"
