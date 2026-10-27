#!/usr/bin/env bash
# CloudSim KPB grid for the demo feedback: every dataset size x k, REPS runs per cell (CloudSim is
# deterministic, so 1 is enough). Each run's log is log_n<N>_k<K>_r<R>.txt in a new session folder,
# in the same metric-line format as the real-world runner, so realworld/analyze_grid.py reads both.
#
#   SOURCE=gocj bash scripts/run_grid_cloudsim.sh        GoCJ 50 (subset), 100..1000
#   SOURCE=synthetic bash scripts/run_grid_cloudsim.sh   Synthetic 1000..10000
#
# ============================================================================================
# run_grid_cloudsim.sh - menjalankan "grid" eksperimen KPB di simulasi CloudSim Plus
# ============================================================================================
# Apa itu grid:
#   Semua kombinasi (ukuran dataset n) x (persentase VM kandidat k) x (repetisi r). Untuk GoCJ:
#   11 ukuran x 3 nilai k (20, 50, 100) x 1 repetisi = 33 run. Setiap run = satu pemanggilan
#   project.Experiment (Java): KPB memetakan n task ke 20 VM, lalu CloudSim mensimulasikannya.
#
# Kenapa REPS default 1:
#   KPB dan CloudSim deterministik: input yang sama selalu memberi hasil yang sama, jadi repetisi
#   tidak menambah informasi (std = 0). Repetisi baru berguna di real-world (lihat realworld/run_grid.sh).
#
# Input (environment variable, semua opsional):
#   SOURCE   gocj (default) atau synthetic -> memilih keluarga dataset di src/main/resources/dataset/
#   SIZES    daftar ukuran dataset (jumlah task), dipisah spasi
#   KS       daftar nilai k (% VM tercepat yang menjadi kandidat KPB), default "20 50 100"
#   REPS     jumlah repetisi per sel (n, k)
#   SESSION  folder output; default results/sessions/cloudsim_<SOURCE>_<timestamp>
#
# Output (di folder $SESSION):
#   parameters.txt              parameter grid + waktu mulai/selesai
#   log_n<N>_k<K>_r<R>.txt      stdout Experiment untuk tiap run (berisi baris metrik)
#   summary.csv, cloudlets_*.csv, mapping_*.json, placement.csv   ditulis Experiment (--out)
#   grid_summary.csv            rekap mean ± std per (n, k), dari realworld/analyze_grid.py
#
# Alur:
#   1) validasi SOURCE, tentukan SIZES  2) buat folder sesi baru  3) compile Java + susun classpath
#   4) loop n -> k -> r: jalankan Experiment, simpan log, cetak ringkasan satu baris
#   5) rekap seluruh sesi dengan analyze_grid.py
# ============================================================================================

# -e: berhenti bila ada perintah gagal; -u: error bila memakai variabel yang belum diset;
# -o pipefail: pipeline dianggap gagal bila salah satu bagiannya gagal.
set -euo pipefail
# Pindah ke root repo (folder induk dari scripts/), supaya semua path relatif di bawah benar
# dari mana pun skrip dipanggil.
cd "$(dirname "$0")/.."

# ${VAR:-default}: pakai nilai dari environment bila ada, kalau tidak pakai default.
SOURCE=${SOURCE:-gocj}
KS=${KS:-"20 50 100"}
REPS=${REPS:-1}
# Ukuran dataset per SOURCE. GoCJ "50" adalah subset 50 task (file khusus, lihat dataset_file).
case $SOURCE in
  gocj) SIZES=${SIZES:-"50 100 200 300 400 500 600 700 800 900 1000"} ;;
  synthetic) SIZES=${SIZES:-"1000 2000 3000 4000 5000 6000 7000 8000 9000 10000"} ;;
  *) echo "[ERROR] SOURCE must be gocj or synthetic" >&2; exit 1 ;;
esac
# Nama folder sesi memakai timestamp, sehingga setiap pemanggilan menghasilkan folder baru.
SESSION=${SESSION:-results/sessions/cloudsim_${SOURCE}_$(date +%Y%m%d_%H%M%S)}
# Folder sesi tidak boleh dipakai ulang: hasil lama tidak tercampur dengan hasil baru
# (Experiment juga menolak summary.csv dengan header berbeda).
if [ -e "$SESSION" ]; then
  echo "[ERROR] $SESSION already exists; sessions are never reused" >&2
  exit 1
fi
mkdir -p "$SESSION"

# dataset_file N: ubah ukuran N menjadi nama file dataset. Untuk GoCJ, N=50 memakai file subset
# (GoCJ_Dataset_50_subset.txt); ukuran lain GoCJ_Dataset_<N>.txt; synthetic Synthetic_Dataset_<N>.txt.
dataset_file() {
  case $SOURCE in
    gocj) if [ "$1" = 50 ]; then echo GoCJ_Dataset_50_subset.txt; else echo "GoCJ_Dataset_$1.txt"; fi ;;
    synthetic) echo "Synthetic_Dataset_$1.txt" ;;
  esac
}

# Compile sekali di awal, lalu tulis classpath semua JAR dependency (CloudSim Plus, Jackson, ...)
# ke target/classpath.txt. Setiap run kemudian memanggil "java" langsung, jauh lebih cepat daripada
# "mvn exec:java" (yang memuat Maven ulang setiap kali).
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# Classpath = kelas hasil compile proyek + JAR dependency (dipisah ':').
CP="target/classes:$(cat target/classpath.txt)"
# Catat parameter grid supaya sesi bisa direproduksi/diaudit.
printf 'source=%s\nsizes=%s\nks=%s\nreps=%s\nstarted=%s\n' "$SOURCE" "$SIZES" "$KS" "$REPS" "$(date -Iseconds)" \
  > "$SESSION/parameters.txt"

# Loop grid: ukuran dataset (n) -> nilai k -> repetisi (r). Urutan tidak diacak karena CloudSim
# tidak dipengaruhi kondisi mesin (berbeda dengan real-world).
for n in $SIZES; do
  dataset=src/main/resources/dataset/$(dataset_file "$n")
  for k in $KS; do
    for r in $(seq 1 "$REPS"); do
      log="$SESSION/log_n${n}_k${k}_r$r.txt"
      # Waktu mulai (detik.nanodetik) untuk menghitung wall-clock satu run.
      start=$(date +%s.%N)
      # -Dlog=error mematikan log CloudSim yang panjang; --out menulis CSV/JSON ke folder sesi.
      # Seluruh stdout disimpan ke file log (dibaca analyze_grid.py).
      java -cp "$CP" -Dlog=error project.Experiment --dataset "$dataset" --k "$k" --out "$SESSION" > "$log"
      # Ringkasan satu baris di terminal: n, k, r, durasi wall-clock (selisih dihitung dengan bc karena
      # bash tidak bisa aritmetika desimal), dan potongan "makespan=...s (nyata/prediksi=...x)" dari log
      # (grep -o hanya mencetak bagian yang cocok). Di CloudSim, "nyata/prediksi" = makespan CloudSim
      # dibagi makespan analitik rumus f1.
      printf 'n=%-6s k=%-4s r=%s %6.1f s wall | %s\n' "$n" "$k" "$r" "$(echo "$(date +%s.%N) - $start" | bc)" \
        "$(grep -oE 'makespan=[0-9.]+s \(nyata/prediksi=[0-9.]+x\)' "$log")"
    done
  done
done
echo "finished=$(date -Iseconds)" >> "$SESSION/parameters.txt"

# Rekap: mean ± std per (n, k) -> $SESSION/grid_summary.csv (dipakai scripts/plot_grid.py).
python3 realworld/analyze_grid.py "$SESSION"
