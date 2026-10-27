#!/usr/bin/env python3
# ============================================================================================
# analyze_grid.py - rekap satu sesi grid (CloudSim maupun real-world)
# ============================================================================================
# Peran:
#   Skrip analisis yang dipanggil di akhir scripts/run_grid_cloudsim.sh dan realworld/run_grid.sh
#   (bisa juga dijalankan manual). Satu sesi grid berisi banyak file log_n<N>_k<K>_r<R>.txt, satu per
#   run. Skrip ini membaca semuanya, mengelompokkan per sel (jumlah task n, k), lalu menghitung
#   rata-rata dan standar deviasi tiap metrik.
#
# Kenapa satu skrip bisa membaca dua sumber:
#   project.Experiment (Java/CloudSim) dan realworld/kpb_realworld.py sengaja mencetak baris metrik
#   dengan format yang PERSIS sama, contoh:
#     prediksi analitik ±12.345 s
#       makespan=12.400s (nyata/prediksi=1.004x), energy=0.001234 kWh, avgResponse=..., ..., vmsUsed=20/20
#   Di CloudSim "nyata/prediksi" = makespan CloudSim / makespan analitik f1; di real-world
#   = makespan nyata / (makespan analitik x scale).
#
# Input : argumen tunggal = path folder sesi.
# Output: <sesi>/grid_summary.csv (dibaca scripts/plot_grid.py) + laporan validasi di terminal:
#   1) tabel mean ± std per (n, k)
#   2) rasio nyata/prediksi antar sel (min/mean/max/CV) dan sel dengan CV makespan > 5%
#   3) drift rasio menurut urutan eksekusi (blok 10 run) -> apakah mesin melambat/mempercepat
#   4) bila ada docker_stats.csv (hanya real-world): median CPU% sibuk per tier VM vs limit container
# ============================================================================================
"""Recap one grid session: mean ± std per (task count, k) from its log_n<N>_k<K>_r<R>.txt files.

Works for CloudSim (scripts/run_grid_cloudsim.sh) and real-world (realworld/run_grid.sh) sessions,
which print the same metric line. Writes <session>/grid_summary.csv and prints the validation:
real/predicted ratio per cell, makespan CV per cell (flag > 5%), and, when docker_stats.csv
exists, busy CPU% per VM tier against the container limits.

  python3 realworld/analyze_grid.py results/sessions/cloudsim_gocj_<timestamp>
"""

import csv
import os
import re
import statistics
import sys
from collections import defaultdict

# Nama file log satu run: log_n<jumlah task>_k<k, boleh desimal>_r<nomor repetisi>.txt
LOG = re.compile(r"^log_n(\d+)_k(\d+(?:\.\d+)?)_r(\d+)\.txt$")
# Baris "prediksi analitik ±X s" -> makespan prediksi (detik).
PREDICTED = re.compile(r"prediksi analitik ±([\d.]+) s")
# Baris metrik utama. (?P<nama>...) = named group, sehingga groupdict() langsung menghasilkan
# dict {nama_metrik: string angka}. Format harus sama persis dengan printf di Experiment.java dan
# print di kpb_realworld.py; bila format salah satu berubah, regex ini juga harus diubah.
LINE = re.compile(
    r"makespan=(?P<makespan_s>[\d.]+)s \(nyata/prediksi=(?P<ratio>[\d.]+)x\), "
    r"energy=(?P<energy_kwh>[\d.]+) kWh, avgResponse=(?P<avg_response_s>[\d.]+)s, "
    r"utilization=(?P<utilization_pct>[\d.]+)%, DI=(?P<di>[\d.]+) \(VM terpakai (?P<di_used>[\d.]+)\), "
    r"throughput=(?P<throughput>[\d.]+) task/s, cost=\$(?P<cost>[\d.]+), "
    r"schedulingTime=(?P<scheduling_ms>[\d.]+) ms, vmsUsed=(?P<vms_used>\d+)/\d+")
# Metrik yang direkap (mean & std). predicted_s berasal dari regex PREDICTED, sisanya dari LINE.
METRICS = ["makespan_s", "energy_kwh", "cost", "utilization_pct", "di", "di_used", "throughput",
           "avg_response_s", "scheduling_ms", "vms_used", "ratio", "predicted_s"]
# Batas coefficient of variation (CV = std / mean x 100%) makespan antar-repetisi; di atas 5% ditandai
# sebagai sel yang hasilnya kurang stabil.
CV_LIMIT = 5.0
# Limit CPU container di docker-compose.yml dalam satuan "docker stats" (100% = 1 core penuh):
# V3 1,0 CPU = 100%, V2 0,3 CPU = 30%, V1 0,1 CPU = 10%.
TIER_LIMIT = {"V3": 100.0, "V2": 30.0, "V1": 10.0}
# Sampel docker stats di bawah 2% dianggap container sedang idle (batch-nya sudah selesai atau sedang
# jeda antar-run) dan tidak dihitung, supaya median mencerminkan CPU% saat container benar-benar bekerja.
BUSY_THRESHOLD = 2.0


def parse(path):
    """Baca satu file log run dan kembalikan dict {metrik: float}.

    Mencari baris metrik (LINE) dan baris prediksi analitik (PREDICTED). Bila salah satu tidak ada
    (mis. run gagal / terhenti di tengah), skrip langsung berhenti dengan pesan error supaya sesi
    yang tidak lengkap tidak diam-diam ikut dirata-rata.
    """
    with open(path) as f:
        text = f.read()
    line, predicted = LINE.search(text), PREDICTED.search(text)
    if not line or not predicted:
        raise SystemExit(f"[ERROR] {path}: no metric line / prediksi analitik")
    values = {k: float(v) for k, v in line.groupdict().items()}
    values["predicted_s"] = float(predicted.group(1))
    return values


def tier(container):
    """Nama container -> tier VM. "kpb_vm07" -> 7 -> V2.

    Mengikuti urutan VM di docker-compose.yml: vm00-03 = V3, vm04-11 = V2, vm12-19 = V1.
    """
    vm = int(container.rsplit("vm", 1)[1])
    return "V3" if vm < 4 else "V2" if vm < 12 else "V1"


def cpu_by_tier(path):
    """Kelompokkan sampel CPU% "sibuk" dari docker_stats.csv per tier (V3/V2/V1).

    Kolom cpu_pct berbentuk "29.87%"; tanda % dibuang lalu diubah ke float. Baris yang tidak bisa
    di-parse (mis. "--" saat container baru start) dilewati. Hanya container kpb_vm* dengan
    CPU >= BUSY_THRESHOLD yang diambil.
    """
    busy = defaultdict(list)
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            try:
                cpu = float(r["cpu_pct"].rstrip("%"))
            except ValueError:
                continue
            if "kpb_vm" in r["container"] and cpu >= BUSY_THRESHOLD:
                busy[tier(r["container"])].append(cpu)
    return busy


def main():
    """Alur utama: kumpulkan log per sel -> hitung mean/std -> tulis CSV -> cetak validasi."""
    # Tepat satu argumen (folder sesi); bila tidak, tampilkan docstring modul sebagai petunjuk pemakaian.
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    session = sys.argv[1]
    # runs[(n, k)] = daftar dict metrik, satu per repetisi.
    runs = defaultdict(list)
    for name in sorted(os.listdir(session)):
        m = LOG.match(name)
        if m:
            runs[(int(m.group(1)), float(m.group(2)))].append(parse(os.path.join(session, name)))
    if not runs:
        raise SystemExit(f"[ERROR] no log_n<N>_k<K>_r<R>.txt in {session}")

    # Satu baris ringkasan per sel (n, k), diurutkan menurut n lalu k. Untuk tiap metrik dibuat kolom
    # <metrik>_mean dan <metrik>_std (std sampel; 0 bila hanya 1 run, mis. CloudSim yang deterministik).
    summary = []
    for (tasks, k), values in sorted(runs.items()):
        row = {"tasks": tasks, "k": f"{k:g}", "n": len(values)}
        for metric in METRICS:
            series = [v[metric] for v in values]
            row[metric + "_mean"] = statistics.mean(series)
            row[metric + "_std"] = statistics.stdev(series) if len(series) > 1 else 0.0
        # CV makespan (%) = seberapa besar variasi antar-repetisi relatif terhadap rata-ratanya.
        row["cv_makespan_pct"] = 100 * row["makespan_s_std"] / row["makespan_s_mean"]
        summary.append(row)
    # Tulis grid_summary.csv; nama kolom diambil dari key baris pertama (urutan dict dipertahankan).
    with open(os.path.join(session, "grid_summary.csv"), "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(summary[0]))
        writer.writeheader()
        writer.writerows(summary)

    # Tabel ringkasan di terminal. Format f-string: >N rata kanan lebar N, <N rata kiri, .3f = 3 desimal.
    # Sel dengan CV makespan > 5% (dan lebih dari 1 run) diberi tanda "<-- CV > 5%".
    print(f"{'task':>6} {'k':>4} {'n':>2} {'makespan (s)':>20} {'energi (kWh)':>18} {'cost ($)':>16} "
          f"{'util %':>7} {'DI':>6} {'nyata/pred':>10} {'CV %':>6}")
    for r in summary:
        flag = "  <-- CV > 5%" if r["n"] > 1 and r["cv_makespan_pct"] > CV_LIMIT else ""
        print(f"{r['tasks']:>6} {r['k']:>4} {r['n']:>2} {r['makespan_s_mean']:>11.3f} ± {r['makespan_s_std']:<6.3f} "
              f"{r['energy_kwh_mean']:>9.5f} ± {r['energy_kwh_std']:<6.5f} {r['cost_mean']:>8.2f} ± {r['cost_std']:<5.2f} "
              f"{r['utilization_pct_mean']:>7.2f} {r['di_mean']:>6.3f} {r['ratio_mean']:>10.3f} "
              f"{r['cv_makespan_pct']:>6.2f}{flag}")

    # Validasi model: rasio nyata/prediksi seharusnya hampir sama di semua sel. CV antar sel yang kecil
    # berarti prediksi analitik (x scale) konsisten untuk semua ukuran dataset dan nilai k.
    # pstdev = std populasi (semua sel dianggap populasi, bukan sampel).
    ratios = [r["ratio_mean"] for r in summary]
    print(f"\nrasio nyata/prediksi antar sel: min {min(ratios):.3f}, mean {statistics.mean(ratios):.3f}, "
          f"max {max(ratios):.3f}, CV {100 * statistics.pstdev(ratios) / statistics.mean(ratios):.2f}%")
    flagged = [f"n={r['tasks']} k={r['k']} ({r['cv_makespan_pct']:.1f}%)" for r in summary
               if r["n"] > 1 and r["cv_makespan_pct"] > CV_LIMIT]
    print("sel CV makespan > 5%: " + (", ".join(flagged) if flagged else "tidak ada")
          + ("" if any(r["n"] > 1 for r in summary) else " (1 run per sel)"))

    # Drift: with scale locked, the ratio should stay flat in execution order (log modification time).
    # Waktu modifikasi file log = kapan run itu selesai, sehingga sorting menurut mtime memberi urutan
    # eksekusi sebenarnya (urutan run real-world diacak, jadi beda dengan urutan nama file).
    # Rasio dirata-rata per blok 10 run: bila rasio naik/turun dari blok ke blok, kecepatan mesin
    # berubah selama sesi (mis. thermal throttling atau mode daya berubah).
    logs = sorted((os.path.getmtime(os.path.join(session, name)), name)
                  for name in os.listdir(session) if LOG.match(name))
    timeline = [parse(os.path.join(session, name))["ratio"] for _, name in logs]
    if len(timeline) >= 10:
        print("\ndrift rasio nyata/prediksi menurut urutan eksekusi (blok 10 run):")
        for a in range(0, len(timeline), 10):
            block = timeline[a:a + 10]
            print(f"  run {a + 1:3d}-{a + len(block):3d}: mean {statistics.mean(block):.3f}  "
                  f"min {min(block):.3f}  max {max(block):.3f}")

    # Hanya sesi real-world yang punya docker_stats.csv. Median CPU% sibuk per tier dibandingkan dengan
    # limit container: bila mendekati limit (mis. V2 ~30%), kuota CPU docker-compose benar-benar berlaku
    # dan rasio kapasitas V3 : V2 : V1 = 10 : 3 : 1 sesuai desain.
    stats = os.path.join(session, "docker_stats.csv")
    if os.path.exists(stats):
        busy = cpu_by_tier(stats)
        print(f"\nCPU% sibuk per tier (sampel >= {BUSY_THRESHOLD:g}%):")
        for t in ("V3", "V2", "V1"):
            if busy[t]:
                print(f"  {t}: median {statistics.median(busy[t]):5.1f}% dari limit {TIER_LIMIT[t]:g}% "
                      f"({len(busy[t])} sampel)")
    print(f"\n-> {os.path.join(session, 'grid_summary.csv')}")


if __name__ == "__main__":
    main()
