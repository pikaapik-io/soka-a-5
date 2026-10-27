#!/usr/bin/env python3
"""Real-World Runner (KPB)

Menjadwalkan task GoCJ ke 20 container Docker, mengeksekusinya secara nyata, lalu
menghitung delapan metrik Design Project bagian 4.1.

Infrastruktur mengikuti desain (lihat docker-compose.yml atau kind-config.yaml):
  * 20 VM: V3 4 PE x 2.500 MIPS, V2 2 PE x 1.500, V1 1 PE x 1.000 (1,0 CPU = 10.000 MIPS)
  * 2 datacenter / 6 host sesuai penempatan VM hasil CloudSim
  * Space-shared: satu VM mengerjakan task berurutan satu per satu (antrean FIFO),
    satu task yang berjalan memakai penuh kapasitas VM-nya (model CloudletSchedulerSpaceShared)

KPB TIDAK diimplementasi ulang di sini: mapping dihitung oleh kode Java yang
sama dengan simulasi (project.Experiment --no-sim --export-mapping), lalu runner ini
hanya mengeksekusinya. Mapping bisa juga dibaca dari JSON yang sudah diekspor (--mapping).

Contoh:
  python3 kpb_realworld.py --dataset ../src/main/resources/dataset/GoCJ_Dataset_100.txt --k 20 --repeat 3
  python3 kpb_realworld.py --dataset ... --mapping mapping.json   (pakai mapping yang sudah diekspor)
  python3 kpb_realworld.py ... --quiet     (tanpa log broker per cloudlet)

Peran file ini dalam proyek
---------------------------
File ini adalah "broker" versi dunia nyata. Kalau di CloudSim Plus ada
DatacenterBroker yang mengirim cloudlet ke VM virtual, di sini skrip ini yang
mengirim task ke 20 container Docker sungguhan (worker/worker.py) lewat HTTP.
Skrip ini dijalankan dari folder realworld/, baik manual maupun oleh run_grid.sh.

Input:
  * File dataset GoCJ / Synthetic (satu panjang task dalam MI per baris).
  * Mapping task -> VM hasil KPB dari Java (dihitung otomatis lewat Maven, atau
    dibaca dari file JSON --mapping).
  * 20 worker yang sudah hidup, tersedia di port localhost 9100-9119.

Output (di folder --out):
  * tasks_<tag>_run<R>.csv : waktu selesai setiap task pada satu run.
  * summary.csv            : satu baris metrik per run.
  * aggregate.csv          : rata-rata dan standar deviasi metrik dari semua run.
  * mapping_<dataset>.json : mapping yang diekspor Java (bila tidak memakai --mapping).

Alur besar (lihat main()):
  1. Baca argumen CLI dan dataset (opsional dipotong --limit).
  2. Cek 20 worker hidup dan bertipe benar, cek placement sama dengan CloudSim.
  3. Ambil mapping KPB dari Java (atau dari JSON), validasi terhadap dataset.
  4. Tentukan "scale" (1 detik simulasi = berapa detik nyata) lewat kalibrasi
     atau nilai yang dikunci --scale, untuk menghitung prediksi makespan.
  5. Ulangi sebanyak --repeat: eksekusi semua task di container, hitung metrik,
     simpan CSV per task dan satu baris summary.
  6. Hitung rata-rata ± std semua run dan simpan ke aggregate.csv.

Cara MIPS ditiru di container
-----------------------------
Container tidak punya konsep "MIPS". Yang bisa diatur Docker adalah kuota CPU
(cpu_quota / cpu_period di docker-compose.yml). Proyek ini memakai skala
1,0 CPU = 10.000 MIPS (REF_MIPS), sehingga V3 (4 x 2.500 = 10.000 MIPS) = 1,0 CPU,
V2 (2 x 1.500 = 3.000 MIPS) = 0,3 CPU, dan V1 (1 x 1.000 = 1.000 MIPS) = 0,1 CPU.
Panjang task L (MI) diubah menjadi beban CPU nyata sebanyak L x iters_per_mi
iterasi loop (lihat burn() di worker.py). Karena kuota CPU sebanding dengan MIPS,
waktu nyata task di VM j sebanding dengan L / C_j, persis seperti di CloudSim,
hanya dikalikan faktor "scale".
"""

import argparse
import csv
import json
import os
import re
import statistics
import subprocess
import threading
import time
import urllib.request
from datetime import datetime

# Root repository (satu folder di atas realworld/), dipakai untuk memanggil Maven
# pada pom.xml proyek Java.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REF_MIPS = 10_000          # 1,0 CPU container = 10.000 MIPS
DEFAULT_ITERS_PER_MI = 14  # beban tetap: L x 14 iterasi CPU
# Contoh: task 900.000 MI = 12.600.000 iterasi loop burn() di worker. Nilai 14
# hanya menentukan "berat" beban nyata (makin besar, makin lama run berjalan,
# tetapi makin kecil pengaruh overhead HTTP/Python terhadap waktu terukur).

# ── Infrastruktur (Design Project bagian 2) ───────────────────────────────────
# Host: PE x MIPS, daya idle/maks (W). Datacenter: biaya (bagian 2.2).
# Data ini adalah salinan spesifikasi yang juga dipakai CloudSim (DatacenterFactory /
# VmFactory di Java), supaya metrik energi dan biaya real-world dihitung dengan
# rumus dan angka yang sama dengan simulasi.
HOST_TYPES = {
    "A": {"pes": 8, "mips": 3_000, "p_idle": 175, "p_max": 250},
    "B": {"pes": 8, "mips": 1_800, "p_idle": 72, "p_max": 120},
}
# cost_sec = biaya per detik CPU, cost_gb_ram = biaya per GB RAM, cost_bw = biaya per
# Mbps bandwidth. DC-1 memakai host tipe A (cepat, boros), DC-2 host tipe B (hemat).
DATACENTERS = {
    1: {"name": "DC-1 Performance", "host_type": "A", "cost_sec": 0.05, "cost_gb_ram": 0.02, "cost_bw": 0.005},
    2: {"name": "DC-2 Efficiency", "host_type": "B", "cost_sec": 0.03, "cost_gb_ram": 0.01, "cost_bw": 0.003},
}
# Daftar 6 host sebagai pasangan (datacenter, indeks host): (1,0) (1,1) (1,2) (2,0) (2,1) (2,2).
HOSTS = [(dc, h) for dc in DATACENTERS for h in range(3)]

# Spesifikasi tiga tipe VM. "capacity" setiap VM = pes x mips (C_j di rumus KPB).
VM_TYPES = {
    "V3": {"pes": 4, "mips": 2_500, "ram_gb": 8, "bw": 1_000},
    "V2": {"pes": 2, "mips": 1_500, "ram_gb": 4, "bw": 1_000},
    "V1": {"pes": 1, "mips": 1_000, "ram_gb": 2, "bw": 500},
}
# Penempatan VM -> (datacenter, host) hasil CloudSim (results/simulation/placement.csv)
# Indeks ke-i list ini adalah penempatan vm<i>. Contoh: PLACEMENT[0] = (1, 0) artinya
# vm00 berada di DC-1, host 0 (Host A0). Dicek ulang oleh check_placement().
PLACEMENT = [(1, 0), (1, 1), (1, 2), (1, 0), (1, 1), (1, 2), (1, 1), (1, 2), (2, 0), (2, 1),
             (2, 2), (2, 0), (2, 1), (2, 2), (2, 1), (2, 2), (2, 0), (2, 1), (2, 2), (2, 0)]
# Bangun daftar 20 VM dengan urutan yang sama seperti VmFactory Java:
# vm00-vm03 = V3, vm04-vm11 = V2, vm12-vm19 = V1. Urutan ini penting karena mapping
# dari Java berisi indeks VM 0..19 yang harus menunjuk ke VM yang sama di sini.
VMS = []
for i, vm_type in enumerate(["V3"] * 4 + ["V2"] * 8 + ["V1"] * 8):
    spec = VM_TYPES[vm_type]
    VMS.append({
        # url: setiap container mem-publish port 8000-nya ke 127.0.0.1:9100+i
        # (lihat "ports" di docker-compose.yml), jadi vm00 -> 9100, vm19 -> 9119.
        "name": f"vm{i:02d}", "type": vm_type, "url": f"http://127.0.0.1:{9100 + i}",
        "pes": spec["pes"], "mips": spec["mips"], "capacity": spec["pes"] * spec["mips"],
        "ram_gb": spec["ram_gb"], "bw": spec["bw"],
        "dc": PLACEMENT[i][0], "host": PLACEMENT[i][1],
    })


# ── Dataset ───────────────────────────────────────────────────────────────────

def load_gocj(path):
    """Sama dengan GoCJLoader.java: angka dipisah spasi/koma/titik koma, '#' komentar.

    Membaca file dataset dan mengembalikan list panjang task (MI) bertipe float,
    dengan urutan sama seperti di file. Indeks list = ID task (cloudlet #i).

    Parameter:
        path: path file dataset (GoCJ_Dataset_<N>.txt atau Synthetic_Dataset_<N>.txt).
    Return:
        list[float] panjang setiap task dalam MI.

    Alur: untuk setiap baris, buang bagian setelah '#' (komentar), lalu pecah sisa
    baris dengan pemisah koma/titik koma/spasi, dan ubah setiap token menjadi float.
    Aturan parsing dibuat identik dengan Java agar jumlah dan urutan task sama.
    """
    lengths = []
    with open(path) as f:
        for line in f:
            content = line.split("#", 1)[0].strip()
            if content:
                lengths += [float(tok) for tok in re.split(r"[,;\s]+", content)]
    return lengths


# ── Penjadwalan (Java, implementasi yang sama dengan simulasi) ────────────────

def java_schedule(args, dataset_path, json_path):
    """Jalankan project.Experiment --no-sim untuk mengekspor mapping ke json_path.

    Fungsi ini memanggil kode Java lewat subprocess Maven, sehingga algoritma KPB
    yang dipakai di real-world PERSIS sama dengan yang dipakai di simulasi CloudSim.

    Parameter:
        args: hasil argparse; hanya args.k (persen VM kandidat KPB) yang dipakai.
        dataset_path: file dataset yang dijadwalkan (bisa file hasil --limit).
        json_path: lokasi file JSON mapping yang akan ditulis oleh Java.
    Return:
        dict isi JSON mapping (lihat load_mapping()).

    Alur:
      1. Susun argumen Java: --dataset, --no-sim (hanya jadwalkan, tanpa simulasi
         CloudSim), --export-mapping <json>, dan --k bila diberikan.
      2. Tolak path yang mengandung spasi, karena -Dexec.args Maven memecah
         argumen berdasarkan spasi.
      3. Jalankan "mvn -q -f pom.xml compile exec:java" dengan main class
         project.Experiment; -Dlog=error menyembunyikan log CloudSim.
      4. Bila Maven gagal atau file JSON tidak terbentuk, hentikan program dan
         tampilkan potongan akhir stdout/stderr (maks 2000 karakter) untuk debugging.
    """
    java_args = ["--dataset", os.path.abspath(dataset_path),
                 "--no-sim", "--export-mapping", os.path.abspath(json_path)]
    if args.k is not None:
        java_args += ["--k", str(args.k)]
    if any(re.search(r"\s", a) for a in java_args):
        raise SystemExit("[ERROR] Path dengan spasi tidak didukung oleh -Dexec.args Maven")
    cmd = ["mvn", "-q", "-f", os.path.join(REPO_ROOT, "pom.xml"), "compile", "exec:java",
           "-Dexec.mainClass=project.Experiment", "-Dlog=error", "-Dexec.args=" + " ".join(java_args)]
    # capture_output=True: output Java tidak dicetak ke layar, hanya dipakai bila gagal.
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(json_path):
        raise SystemExit("[ERROR] Penjadwalan Java gagal:\n" + proc.stdout[-2000:] + proc.stderr[-2000:])
    return load_mapping(json_path)


def load_mapping(path):
    """Baca file JSON mapping yang ditulis Experiment.writeMappingJson() di Java.

    Isi JSON (dict) yang dipakai runner ini:
        scheduler           : nama scheduler (mis. "KPB").
        dataset, tasks      : nama dataset dan jumlah task.
        k_percent           : nilai k (persen VM kandidat) yang dipakai KPB.
        analytic_makespan_s : makespan rumus antrean (sum L_i / C_j per VM, ambil max),
                              dalam detik simulasi.
        analytic_energy_kwh : energi rumus analitik (tidak dipakai di sini).
        scheduling_ms       : waktu komputasi KPB di Java (metrik scheduling time).
        vm_capacity         : kapasitas C_j (MIPS x PE) ke-20 VM.
        mapping             : list, mapping[i] = indeks VM (0..19) untuk task i.
    """
    with open(path) as f:
        return json.load(f)


def check_mapping(schedule, lengths):
    """Mapping harus cocok dengan dataset dan infrastruktur runner ini.

    Tiga pemeriksaan agar tidak menjalankan mapping yang salah:
      1. Jumlah task di JSON dan panjang list mapping sama dengan jumlah task dataset.
      2. Kapasitas VM yang dipakai Java sama dengan kapasitas VMS di file ini
         (urutan dan nilai harus identik).
      3. Setiap indeks VM di mapping berada di rentang 0..19.
    Bila ada yang tidak cocok, program dihentikan dengan pesan error.
    """
    mapping = schedule["mapping"]
    if schedule["tasks"] != len(lengths) or len(mapping) != len(lengths):
        raise SystemExit(f"[ERROR] Mapping untuk {schedule['tasks']} task, dataset punya {len(lengths)} task")
    if [float(c) for c in schedule["vm_capacity"]] != [float(vm["capacity"]) for vm in VMS]:
        raise SystemExit("[ERROR] Kapasitas VM di mapping beda dengan VMS runner ini")
    if any(not 0 <= vm < len(VMS) for vm in mapping):
        raise SystemExit("[ERROR] Mapping berisi indeks VM tidak valid")


# ── Komunikasi dengan worker ──────────────────────────────────────────────────

def post_stream(url, payload, timeout=7200):
    """POST lalu baca balasan NDJSON worker baris per baris, begitu barisnya dikirim.

    NDJSON = satu objek JSON per baris. Worker mengirim satu baris setiap kali satu
    task selesai, jadi fungsi ini adalah generator: ia meng-"yield" setiap event
    segera setelah barisnya tiba, tanpa menunggu seluruh batch selesai.

    Parameter:
        url: endpoint worker, mis. "http://127.0.0.1:9100/batch".
        payload: dict yang dikirim sebagai body JSON.
        timeout: batas waktu socket (detik) tanpa data masuk; 7200 s = 2 jam,
                 karena satu task besar di VM V1 bisa berjalan lama.
    Yield:
        dict setiap baris balasan, mis. {"id": 3, "finish": ...} atau
        {"vm": "vm00", "cpu_s": ...} (baris terakhir).
    """
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        # Iterasi objek respons = membaca baris demi baris dari stream HTTP.
        for line in resp:
            if line.strip():
                yield json.loads(line)


def where(vm):
    """Contoh: vm00 (V3) di DC-1/Host A0.

    Membentuk teks lokasi VM (nama, tipe, datacenter, host) untuk log broker.
    Huruf host (A/B) diambil dari tipe host datacenter-nya.
    """
    return f"{vm['name']} ({vm['type']}) di DC-{vm['dc']}/Host {DATACENTERS[vm['dc']]['host_type']}{vm['host']}"


def check_workers():
    """Pastikan ke-20 worker hidup dan menjalankan worker versi yang benar.

    Untuk setiap VM, panggil GET <url>/health (timeout 5 detik) lalu periksa:
      * worker merespons pada port localhost yang dikonfigurasi;
      * flag "sequential" bernilai true, artinya worker memakai model antrean
        sekuensial (space-shared), bukan versi lama yang paralel per-PE;
      * "vm_type" container sama dengan tipe VM yang diharapkan runner
        (mencegah salah urutan port atau konfigurasi deployment lama).
    Program berhenti dengan pesan perbaikan bila salah satu pemeriksaan gagal.
    """
    for vm in VMS:
        try:
            with urllib.request.urlopen(vm["url"] + "/health", timeout=5) as resp:
                health = json.loads(resp.read())
        except OSError as exc:
            raise SystemExit(f"[ERROR] {vm['name']} ({vm['url']}) tidak merespons: {exc}\n"
                             "        Jalankan worker dengan Docker Compose atau deploy Kind "
                             "(kubectl apply -f k8s-topology.yaml); pastikan port 9100-9119 tersedia")
        if not health.get("sequential"):
            raise SystemExit(f"[ERROR] {vm['name']} masih memakai worker versi lama (model paralel per-PE, "
                             "bukan antrean sekuensial).\n"
                             "        Recreate worker Docker atau apply ulang manifest Kubernetes")
        if health.get("vm_type") != vm["type"]:
            raise SystemExit(f"[ERROR] {vm['name']} bertipe {health.get('vm_type')}, seharusnya {vm['type']}.\n"
                             "        Recreate worker Docker atau apply ulang manifest Kubernetes")


def check_placement(path):
    """Cocokkan PLACEMENT dengan hasil CloudSim bila file-nya ada.

    Parameter:
        path: file placement.csv yang ditulis simulasi CloudSim (kolom "datacenter"
              dan "host" per VM, urut vm00..vm19).
    Return:
        string catatan untuk dicetak di header output.

    Alur: bila file belum ada, kembalikan catatan bahwa verifikasi dilewati.
    Bila ada, bandingkan baris demi baris dengan PLACEMENT; jika satu VM saja beda
    datacenter/host, program berhenti (karena energi & biaya per host akan salah).
    """
    if not os.path.exists(path):
        return "placement CloudSim belum ada (jalankan simulasi dulu untuk verifikasi)"
    with open(path) as f:
        rows = list(csv.DictReader(f))
    for row, vm in zip(rows, VMS):
        if (int(row["datacenter"]), int(row["host"])) != (vm["dc"], vm["host"]):
            raise SystemExit(f"[ERROR] Penempatan {vm['name']} beda dengan CloudSim: "
                             f"DC{row['datacenter']}/H{row['host']} vs DC{vm['dc']}/H{vm['host']}")
    return "placement cocok dengan CloudSim"


def calibrate(iterations=2_000_000):
    """Iterasi per detik setara 1,0 CPU (REF_MIPS), diukur pada VM V3 (kapasitas = REF_MIPS).

    Tujuan: mengukur seberapa cepat mesin ini menjalankan loop burn() pada kuota
    1,0 CPU. Hasilnya dipakai main() untuk menghitung "scale":
        scale = iters_per_mi x REF_MIPS / rate
    Penjelasan: task L MI di VM berkapasitas C_j butuh L / C_j detik simulasi.
    Di container, task itu = L x iters_per_mi iterasi dengan kecepatan
    rate x (C_j / REF_MIPS) iterasi/detik, jadi waktu nyatanya
    (L / C_j) x (iters_per_mi x REF_MIPS / rate) = detik simulasi x scale.

    Parameter:
        iterations: jumlah iterasi uji (default 2 juta).
    Return:
        float, iterasi per detik untuk 1,0 CPU.

    Alur: kirim satu task uji ke vm00 (V3 = 1,0 CPU), catat waktu kirim t0, dan
    ambil waktu "finish" yang dilaporkan worker. Waktu finish diukur dengan
    time.monotonic() di dalam container; selisihnya dengan t0 (monotonic di host)
    bermakna karena container berbagi kernel (jam monotonic) dengan mesin Docker-nya.
    Faktor REF_MIPS / capacity menormalkan hasil ke 1,0 CPU (untuk V3 nilainya 1).
    """
    t0 = time.monotonic()
    elapsed = None
    for event in post_stream(VMS[0]["url"] + "/batch", {"tasks": [{"id": 0, "iterations": iterations}]}):
        if "id" in event:
            elapsed = event["finish"] - t0
    return iterations / elapsed * (REF_MIPS / VMS[0]["capacity"])


# ── Eksekusi satu run ─────────────────────────────────────────────────────────

def execute(lengths, mapping, iters_per_mi, live=True):
    """Kirim satu batch per VM, lalu tampilkan setiap cloudlet yang selesai secara real-time.

    Waktu di log = detik sejak batch dikirim (t0). Waktu selesai diukur di dalam
    container, jadi pencetakan log tidak memengaruhi metrik.

    Parameter:
        lengths: list panjang task (MI), indeks = ID task.
        mapping: list, mapping[i] = indeks VM untuk task i (hasil KPB).
        iters_per_mi: berapa iterasi CPU untuk setiap 1 MI.
        live: True = cetak log broker per cloudlet; False (--quiet) = diam.
    Return:
        (records, cpu_s)
        records: list dict per task (task_id, length_mi, vm, vm_type, datacenter,
                 host, finish_s) diurutkan menurut task_id.
        cpu_s: list detik-CPU yang dipakai setiap container (dari cgroup), untuk energi.

    Alur:
      1. Kelompokkan task per VM sesuai mapping. Urutan task dalam satu batch =
         urutan ID task, dan worker mengerjakannya FIFO dalam urutan itu (sama
         seperti antrean CloudletSchedulerSpaceShared).
      2. Cetak log "Mengirim Cloudlet" untuk semua task (waktu 0), lalu catat t0.
         Semua task dianggap tiba bersamaan di t = 0, seperti di CloudSim.
      3. Buat satu thread per VM yang punya task; setiap thread mengirim batch
         VM-nya lewat post_stream() sehingga ke-20 VM bekerja paralel, sedangkan
         di dalam tiap VM task berjalan berurutan.
      4. Setiap event "selesai" dari worker dicatat sebagai finish = waktu monotonic
         worker - t0. Lock dipakai karena dict finish, list cpu_s, dan print
         diakses banyak thread sekaligus.
      5. Setelah semua thread selesai (join), pastikan tidak ada error dan semua
         task benar-benar selesai, lalu susun records.
    """
    # batches[j] = daftar task untuk VM j; jumlah iterasi = L x iters_per_mi.
    batches = {j: [] for j in range(len(VMS))}
    for task, vm in enumerate(mapping):
        batches[vm].append({"id": task, "iterations": int(lengths[task] * iters_per_mi)})

    total = len(mapping)
    finish = {}  # task -> (indeks VM, detik sejak t0)
    cpu_s = [0.0] * len(VMS)
    errors = []
    lock = threading.Lock()

    def log(t, message):
        """Cetak satu baris log bergaya broker CloudSim: '<waktu>: Broker: <pesan>'."""
        if live:
            print(f"{t:9.2f}: Broker: {message}", flush=True)

    for task, vm in enumerate(mapping):
        log(0.0, f"Mengirim Cloudlet #{task} ({int(lengths[task])} MI) ke {where(VMS[vm])}")
    # t0 = titik nol waktu run (setara t = 0 di CloudSim). Semua finish_s dan
    # makespan diukur relatif terhadap t0.
    t0 = time.monotonic()

    def run_vm(j):
        """Isi satu thread: kirim batch VM j dan proses event yang di-stream worker.

        Event dengan kunci "id" = satu task selesai -> simpan waktu selesainya.
        Event tanpa "id" (baris terakhir) = ringkasan VM -> simpan cpu_s (detik CPU
        cgroup yang dipakai container selama batch). Error (mis. koneksi putus)
        tidak langsung menghentikan program, tetapi dikumpulkan ke list errors.
        """
        vm = VMS[j]
        try:
            for event in post_stream(vm["url"] + "/batch", {"tasks": batches[j]}):
                with lock:
                    if "id" in event:
                        # Waktu selesai = jam monotonic di container - t0 di runner,
                        # jadi keterlambatan print/log di runner tidak ikut terukur.
                        task, t = int(event["id"]), event["finish"] - t0
                        finish[task] = (j, t)
                        log(t, f"Cloudlet #{task} selesai di {where(vm)}, diterima broker")
                        log(t, f"Jumlah cloudlet selesai: {len(finish)}/{total}")
                    else:
                        cpu_s[j] = event["cpu_s"]
                        log(time.monotonic() - t0, f"{vm['name']} ({vm['type']}) selesai: "
                            f"{len(batches[j])} cloudlet, CPU terpakai {cpu_s[j]:.2f} s")
        except Exception as exc:  # noqa: BLE001 - dilaporkan setelah join
            with lock:
                errors.append(f"{vm['name']}: {exc}")

    # Hanya VM yang mendapat task yang diberi thread; VM kosong tetap idle (T_j = 0).
    threads = [threading.Thread(target=run_vm, args=(j,)) for j in batches if batches[j]]
    for t in threads:
        t.start()
    # Tunggu semua VM selesai mengerjakan batch-nya.
    for t in threads:
        t.join()
    if errors:
        raise SystemExit("[ERROR] " + "; ".join(errors))
    if len(finish) != total:
        raise SystemExit(f"[ERROR] Hanya {len(finish)} dari {total} cloudlet yang selesai")
    log(max(t for _, t in finish.values()), f"Semua cloudlet selesai ({total}/{total}). Finishing...")

    # Susun hasil per task (format sama dengan cloudlets_*.csv di simulasi).
    records = []
    for task, (j, t) in finish.items():
        records.append({
            "task_id": task, "length_mi": int(lengths[task]),
            "vm": VMS[j]["name"], "vm_type": VMS[j]["type"],
            "datacenter": VMS[j]["dc"], "host": VMS[j]["host"],
            "finish_s": round(t, 4),
        })
    return sorted(records, key=lambda r: r["task_id"]), cpu_s


# ── Metrik (Design Project bagian 4.1) ────────────────────────────────────────

def degree_of_imbalance(times):
    """Degree of Imbalance: DI = (T_max - T_min) / T_avg.

    Parameter:
        times: list waktu selesai VM (T_j).
    Return:
        float DI; 0 bila list kosong atau rata-ratanya 0.
    DI = 0 berarti semua VM selesai bersamaan (beban seimbang sempurna);
    makin besar DI, makin timpang pembagian beban. Rumus sama dengan
    MetricsCollector.degreeOfImbalance() di Java.
    """
    avg = sum(times) / len(times) if times else 0
    return (max(times) - min(times)) / avg if avg > 0 else 0.0


def compute_metrics(records, cpu_s):
    """Hitung metrik Design Project bagian 4.1 dari hasil satu run nyata.

    Rumus dibuat sama dengan MetricsCollector.java (simulasi CloudSim), agar hasil
    real-world dan simulasi bisa dibandingkan langsung.

    Parameter:
        records: hasil execute(), satu dict per task (dipakai finish_s dan vm).
        cpu_s: detik-CPU nyata per container (dari cgroup), dipakai untuk energi.
    Return:
        dict metrik:
          tasks             : jumlah task.
          makespan_s        : Cmax = max_j T_j, T_j = waktu selesai task terakhir di VM j.
          energy_kwh        : energi 6 host selama makespan (model daya linear).
          avg_response_s    : rata-rata response time = rata-rata (finish_i - 0), karena
                              semua task tiba di t = 0. Response time ini sudah memuat
                              waktu tunggu di antrean VM + waktu eksekusi task itu sendiri.
          utilization_pct   : 100 x sum_j T_j / (m x Cmax), m = 20 VM. Karena VM
                              space-shared sibuk terus dari 0 sampai T_j, ini adalah
                              persentase waktu VM sibuk dibanding panjang run.
          di_all_vm         : DI dari T_j semua 20 VM (VM tanpa task ikut, T_j = 0).
          di_used_vm        : DI hanya dari VM yang mendapat task (T_j > 0).
          throughput_task_s : jumlah task / makespan.
          cost_usd          : biaya CPU + RAM + BW semua VM.
          vms_used          : jumlah VM yang mendapat minimal satu task.
    """
    # T_j: waktu selesai task terakhir di setiap VM. Indeks VM diambil dari nama
    # ("vm07"[2:] -> "07" -> 7). VM tanpa task tetap 0.
    vm_finish = [0.0] * len(VMS)
    for r in records:
        j = int(r["vm"][2:])
        vm_finish[j] = max(vm_finish[j], r["finish_s"])
    makespan = max(vm_finish)

    # Energi: P(u) = P_idle + (P_max - P_idle) * u untuk 6 host dari 0 sampai Cmax.
    # integral u_k dt = CPU-detik VM di host k / kapasitas CPU host k.
    # Penjelasan:
    #   * host_cpu = kapasitas host dalam satuan "CPU container" (skala REF_MIPS),
    #     mis. host A = 8 x 3.000 / 10.000 = 2,4 CPU, host B = 8 x 1.800 / 10.000 = 1,44 CPU.
    #   * busy = total detik-CPU yang benar-benar dipakai container-container yang
    #     ditempatkan di host itu (diukur cgroup di worker).
    #   * busy / host_cpu = integral utilisasi host terhadap waktu.
    #   * Semua host dianggap menyala selama makespan, jadi energi idle = P_idle x Cmax.
    joules = 0.0
    for dc, h in HOSTS:
        host = HOST_TYPES[DATACENTERS[dc]["host_type"]]
        host_cpu = host["pes"] * host["mips"] / REF_MIPS
        busy = sum(cpu_s[j] for j, vm in enumerate(VMS) if (vm["dc"], vm["host"]) == (dc, h))
        joules += host["p_idle"] * makespan + (host["p_max"] - host["p_idle"]) * busy / host_cpu

    # Biaya: rumus yang sama dengan VmCost CloudSim (CPU + RAM + BW), VM hidup selama Cmax.
    # Biaya CPU = cost_sec / MIPS-per-PE host x MIPS total VM x lama hidup VM
    #           = cost_sec x (capacity / mips host) x makespan.
    # Biaya RAM = cost_gb_ram x RAM VM (GB); biaya BW = cost_bw x bandwidth VM.
    # Biaya storage sengaja tidak dihitung (bukan bagian metrik).
    cost = 0.0
    for vm in VMS:
        dc = DATACENTERS[vm["dc"]]
        host_mips_per_pe = HOST_TYPES[dc["host_type"]]["mips"]
        cost += dc["cost_sec"] * (vm["capacity"] / host_mips_per_pe) * makespan
        cost += dc["cost_gb_ram"] * vm["ram_gb"] + dc["cost_bw"] * vm["bw"]

    return {
        "tasks": len(records),
        "makespan_s": makespan,
        # 1 kWh = 3.600.000 Joule.
        "energy_kwh": joules / 3_600_000,
        "avg_response_s": sum(r["finish_s"] for r in records) / len(records),
        "utilization_pct": 100 * sum(vm_finish) / (len(VMS) * makespan),
        "di_all_vm": degree_of_imbalance(vm_finish),
        "di_used_vm": degree_of_imbalance([t for t in vm_finish if t > 0]),
        "throughput_task_s": len(records) / makespan,
        "cost_usd": cost,
        "vms_used": sum(1 for t in vm_finish if t > 0),
    }


# ── Output ────────────────────────────────────────────────────────────────────

def append_csv(path, row):
    """Tambahkan satu baris dict ke file CSV (summary.csv / aggregate.csv).

    Bila file belum ada, header ditulis dulu. Bila sudah ada, header lama harus
    sama persis dengan kunci row; kalau berbeda (format kolom berubah), program
    berhenti agar data lama tidak tercampur dengan format baru.
    """
    new_file = not os.path.exists(path)
    if not new_file:
        with open(path, newline="") as f:
            header = next(csv.reader(f), None)
        if header != list(row):
            raise SystemExit(f"[ERROR] {path} punya kolom berbeda; pakai folder --out baru")
    with open(path, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(row))
        if new_file:
            writer.writeheader()
        writer.writerow(row)


def write_csv(path, rows):
    """Tulis (timpa) file CSV dari list dict; kolom diambil dari kunci baris pertama.

    Dipakai untuk tasks_<tag>_run<R>.csv (satu baris per task).
    """
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


# Metrik yang disimpan per run di summary.csv dan dirata-rata di aggregate.csv.
# scheduling_ms bukan diukur di container, tetapi waktu komputasi KPB dari Java.
METRICS = ["makespan_s", "energy_kwh", "avg_response_s", "utilization_pct", "di_all_vm",
           "di_used_vm", "throughput_task_s", "cost_usd", "scheduling_ms"]


def main():
    """Titik masuk program: baca argumen CLI lalu jalankan seluruh alur real-world.

    Argumen CLI:
        --dataset        file dataset (default GoCJ_Dataset_100.txt).
        --mapping        pakai JSON mapping yang sudah diekspor (Java tidak dipanggil, --k diabaikan).
        --k              persen VM kandidat KPB, (0, 100]; default di Java 20.
        --repeat         jumlah repetisi eksekusi (default 3).
        --first-run      nomor run pertama, agar nama file tidak tertimpa antar-pemanggilan.
        --calibrate-only hanya kalibrasi lalu cetak scale (dipakai run_grid.sh).
        --iters-per-mi   iterasi CPU per MI (default 14).
        --scale          kunci nilai scale (lewati kalibrasi).
        --limit          hanya pakai N task pertama dataset.
        --out            folder hasil (default results/sessions/kpb_<timestamp>).
        --placement      file placement.csv CloudSim untuk verifikasi penempatan VM.
        --quiet          sembunyikan log broker per cloudlet.

    Alur lengkap dijelaskan di docstring modul (langkah 1-6) dan di komentar di bawah.
    """
    parser = argparse.ArgumentParser(description="Real-world runner (Docker/Kubernetes): KPB")
    parser.add_argument("--dataset", default="../src/main/resources/dataset/GoCJ_Dataset_100.txt")
    parser.add_argument("--mapping", help="JSON mapping hasil project.Experiment --export-mapping "
                                          "(dipakai apa adanya, --k diabaikan)")
    parser.add_argument("--k", type=float, help="KPB: persen VM kandidat (0, 100], default Java 20")
    parser.add_argument("--repeat", type=int, default=3, help="jumlah repetisi (default 3)")
    parser.add_argument("--first-run", type=int, default=1,
                        help="nomor run pertama, agar repetisi dari beberapa pemanggilan tidak saling timpa")
    parser.add_argument("--calibrate-only", action="store_true",
                        help="cetak scale hasil kalibrasi lalu keluar (untuk mengunci --scale di grid)")
    parser.add_argument("--iters-per-mi", type=float, default=DEFAULT_ITERS_PER_MI,
                        help="iterasi CPU per MI (beban tetap, default 14)")
    parser.add_argument("--scale", type=float, default=None,
                        help="kunci skala detik-nyata/detik-simulasi (lewati kalibrasi otomatis)")
    parser.add_argument("--limit", type=int, default=0, help="hanya pakai N task pertama")
    parser.add_argument("--out", help="folder sesi (default results/sessions/kpb_<timestamp>)")
    parser.add_argument("--placement", default="../results/simulation/placement.csv")
    parser.add_argument("--quiet", action="store_true", help="sembunyikan log broker per cloudlet")
    args = parser.parse_args()
    if args.repeat < 1:
        raise SystemExit("--repeat minimal 1")

    # Mode kalibrasi saja: run_grid.sh memanggil ini beberapa kali, mengambil median
    # scale-nya, lalu mengunci scale itu (--scale) untuk semua run di grid.
    if args.calibrate_only:
        check_workers()
        print(f"scale={args.iters_per_mi * REF_MIPS / calibrate():.6f} itersPerMI={args.iters_per_mi:g}")
        return

    # 1. Baca dataset dan siapkan folder output.
    lengths = load_gocj(args.dataset)
    dataset_name = os.path.splitext(os.path.basename(args.dataset))[0]
    out = args.out or os.path.join("results", "sessions",
                                   f"kpb_{datetime.now().strftime('%Y%m%d_%H%M%S')}")
    os.makedirs(out, exist_ok=True)
    dataset_path = args.dataset
    # --limit: potong dataset, lalu tulis potongannya ke file baru di folder output,
    # supaya Java menjadwalkan task yang persis sama dengan yang dieksekusi di sini.
    if args.limit:
        lengths = lengths[:args.limit]
        dataset_path = os.path.join(out, f"{dataset_name}_first{args.limit}.txt")
        with open(dataset_path, "w") as f:
            f.write("\n".join(f"{x:g}" for x in lengths) + "\n")

    # 2. Verifikasi infrastruktur: worker hidup + tipe benar, placement = CloudSim.
    check_workers()
    placement_note = check_placement(args.placement)
    # 3. Ambil mapping KPB: dari file JSON (--mapping) atau dihitung Java sekarang.
    if args.mapping:
        schedule = load_mapping(args.mapping)
    else:
        schedule = java_schedule(args, dataset_path, os.path.join(out, f"mapping_{dataset_name}.json"))
    check_mapping(schedule, lengths)
    mapping = schedule["mapping"]

    # 4. Tentukan scale (konversi detik simulasi -> detik nyata). Lihat calibrate().
    if args.scale is not None:
        scale = args.scale  # 1 detik simulasi = scale detik nyata (dikunci lewat CLI)
        scale_note = "locked"
    else:
        rate = calibrate()
        scale = args.iters_per_mi * REF_MIPS / rate  # 1 detik simulasi = scale detik nyata
        scale_note = "calibrated"
    # Prediksi makespan nyata = makespan analitik (detik simulasi, dari Java) x scale.
    # Dibandingkan dengan makespan terukur sebagai rasio "nyata/prediksi".
    predicted = schedule["analytic_makespan_s"] * scale

    # tag dipakai di nama file per task, mis. GoCJ_Dataset_100_n100_k20.
    k_text = f"{schedule['k_percent']:g}"
    tag = f"{dataset_name}_n{len(lengths)}_k{k_text}"
    print(f"scheduler={schedule['scheduler']} realworld, {placement_note}")
    print(f"dataset={args.dataset} tasks={len(lengths)} "
          f"repeat={args.repeat} itersPerMI={args.iters_per_mi:g} scale={scale:.4f} ({scale_note})")

    # 5. Eksekusi berulang. Mapping sama untuk setiap run (KPB deterministik); yang
    #    berbeda hanya noise eksekusi nyata, sehingga std menunjukkan kestabilan.
    runs = []
    for run in range(args.first_run, args.first_run + args.repeat):
        print(f"[run {run}] menjalankan task di container... "
              f"(prediksi analitik ±{predicted:.1f} s)", flush=True)
        records, cpu_s = execute(lengths, mapping, args.iters_per_mi, live=not args.quiet)
        m = compute_metrics(records, cpu_s)
        m["scheduling_ms"] = schedule["scheduling_ms"]
        runs.append(m)

        # Format baris ini sama dengan output Java (Experiment), sehingga
        # analyze_grid.py bisa mem-parse log real-world dan CloudSim dengan parser yang sama.
        ratio = m['makespan_s'] / predicted if predicted > 0 else float('nan')
        print(f"  makespan={m['makespan_s']:.3f}s (nyata/prediksi={ratio:.3f}x), "
              f"energy={m['energy_kwh']:.6f} kWh, "
              f"avgResponse={m['avg_response_s']:.3f}s, utilization={m['utilization_pct']:.2f}%, "
              f"DI={m['di_all_vm']:.4f} (VM terpakai {m['di_used_vm']:.4f}), "
              f"throughput={m['throughput_task_s']:.3f} task/s, cost=${m['cost_usd']:.2f}, "
              f"schedulingTime={m['scheduling_ms']:.3f} ms, vmsUsed={m['vms_used']}/20", flush=True)

        # Simpan detail per task untuk run ini, lalu tambahkan satu baris ke summary.csv.
        write_csv(os.path.join(out, f"tasks_{tag}_run{run}.csv"), records)
        row = {"timestamp": datetime.now().isoformat(timespec="seconds"), "dataset": dataset_name,
               "tasks": m["tasks"], "k_percent": k_text, "run": run,
               "iters_per_mi": args.iters_per_mi, "scale": round(scale, 5),
               "predicted_makespan_s": round(predicted, 4)}
        row.update({key: round(m[key], 6) for key in METRICS})
        row["vms_used"] = m["vms_used"]
        append_csv(os.path.join(out, "summary.csv"), row)

    # 6. Agregasi: rata-rata dan standar deviasi sampel setiap metrik dari semua run
    #    (std = 0 bila hanya satu run, karena stdev butuh minimal dua nilai).
    agg = {"dataset": dataset_name, "tasks": len(lengths), "k_percent": k_text, "runs": len(runs)}
    print(f"rata-rata ± std dari {len(runs)} run:")
    for key in METRICS:
        values = [r[key] for r in runs]
        mean = statistics.mean(values)
        std = statistics.stdev(values) if len(values) > 1 else 0.0
        agg[f"{key}_mean"], agg[f"{key}_std"] = round(mean, 6), round(std, 6)
        print(f"  {key:<18} {mean:12.4f} ± {std:.4f}")
    append_csv(os.path.join(out, "aggregate.csv"), agg)
    print(f"hasil -> {out}/summary.csv, {out}/aggregate.csv, {out}/tasks_{tag}_run*.csv")


if __name__ == "__main__":
    main()
