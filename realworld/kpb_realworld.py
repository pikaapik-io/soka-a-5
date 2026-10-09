#!/usr/bin/env python3
"""Real-World Runner (KPB / Harmony Search / Clonal Selection)

Menjadwalkan task GoCJ ke 20 container Docker, mengeksekusinya secara nyata, lalu
menghitung delapan metrik Design Project bagian 4.1.

Infrastruktur mengikuti desain (lihat docker-compose.yml):
  * 20 VM: V3 4 PE x 2.500 MIPS, V2 2 PE x 1.500, V1 1 PE x 1.000 (1,0 CPU = 10.000 MIPS)
  * 2 datacenter / 6 host sesuai penempatan VM hasil CloudSim
  * Space-shared: satu VM mengerjakan task berurutan satu per satu (antrean FIFO),
    satu task yang berjalan memakai penuh kapasitas VM-nya (model CloudletSchedulerSpaceShared)

Algoritma TIDAK diimplementasi ulang di sini: mapping dihitung oleh kode Java yang
sama dengan simulasi (project.Experiment --no-sim --export-mapping), lalu runner ini
hanya mengeksekusinya. Mapping bisa juga dibaca dari JSON yang sudah diekspor (--mapping).

Contoh:
  python3 kpb_realworld.py --dataset ../src/main/resources/dataset/GoCJ_Dataset_100.txt --k 20 --repeat 3
  python3 kpb_realworld.py --dataset ... --algo csa --seed 1 --evals 10000
  python3 kpb_realworld.py --dataset ... --mapping mapping.json   (pakai mapping yang sudah diekspor)
  python3 kpb_realworld.py ... --quiet     (tanpa log broker per cloudlet)
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

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REF_MIPS = 10_000          # 1,0 CPU container = 10.000 MIPS
DEFAULT_ITERS_PER_MI = 14  # beban tetap: L x 14 iterasi CPU

# ── Infrastruktur (Design Project bagian 2) ───────────────────────────────────
# Host: PE x MIPS, daya idle/maks (W). Datacenter: biaya (bagian 2.2).
HOST_TYPES = {
    "A": {"pes": 8, "mips": 3_000, "p_idle": 175, "p_max": 250},
    "B": {"pes": 8, "mips": 1_800, "p_idle": 72, "p_max": 120},
}
DATACENTERS = {
    1: {"name": "DC-1 Performance", "host_type": "A", "cost_sec": 0.05, "cost_gb_ram": 0.02, "cost_bw": 0.005},
    2: {"name": "DC-2 Efficiency", "host_type": "B", "cost_sec": 0.03, "cost_gb_ram": 0.01, "cost_bw": 0.003},
}
HOSTS = [(dc, h) for dc in DATACENTERS for h in range(3)]

VM_TYPES = {
    "V3": {"pes": 4, "mips": 2_500, "ram_gb": 8, "bw": 1_000},
    "V2": {"pes": 2, "mips": 1_500, "ram_gb": 4, "bw": 1_000},
    "V1": {"pes": 1, "mips": 1_000, "ram_gb": 2, "bw": 500},
}
# Penempatan VM -> (datacenter, host) hasil CloudSim (results/simulation/placement.csv)
PLACEMENT = [(1, 0), (1, 1), (1, 2), (1, 0), (1, 1), (1, 2), (1, 1), (1, 2), (2, 0), (2, 1),
             (2, 2), (2, 0), (2, 1), (2, 2), (2, 1), (2, 2), (2, 0), (2, 1), (2, 2), (2, 0)]
VMS = []
for i, vm_type in enumerate(["V3"] * 4 + ["V2"] * 8 + ["V1"] * 8):
    spec = VM_TYPES[vm_type]
    VMS.append({
        "name": f"vm{i:02d}", "type": vm_type, "url": f"http://127.0.0.1:{9100 + i}",
        "pes": spec["pes"], "mips": spec["mips"], "capacity": spec["pes"] * spec["mips"],
        "ram_gb": spec["ram_gb"], "bw": spec["bw"],
        "dc": PLACEMENT[i][0], "host": PLACEMENT[i][1],
    })


# ── Dataset ───────────────────────────────────────────────────────────────────

def load_gocj(path):
    """Sama dengan GoCJLoader.java: angka dipisah spasi/koma/titik koma, '#' komentar."""
    lengths = []
    with open(path) as f:
        for line in f:
            content = line.split("#", 1)[0].strip()
            if content:
                lengths += [float(tok) for tok in re.split(r"[,;\s]+", content)]
    return lengths


# ── Penjadwalan (Java, implementasi yang sama dengan simulasi) ────────────────

# Opsi algoritma yang diteruskan apa adanya ke project.Experiment (lihat RunOptions.java).
JAVA_OPTIONS = ["k", "seed", "evals", "w", "hms", "hmcr", "par", "pop", "nselect", "beta",
                "mut_min", "mut_max", "d"]


def java_schedule(args, dataset_path, json_path):
    """Jalankan project.Experiment --no-sim untuk mengekspor mapping ke json_path."""
    java_args = ["--dataset", os.path.abspath(dataset_path), "--algo", args.algo,
                 "--no-sim", "--export-mapping", os.path.abspath(json_path)]
    for name in JAVA_OPTIONS:
        value = getattr(args, name)
        if value is not None:
            java_args += ["--" + name.replace("_", "-"), str(value)]
    if any(re.search(r"\s", a) for a in java_args):
        raise SystemExit("[ERROR] Path dengan spasi tidak didukung oleh -Dexec.args Maven")
    cmd = ["mvn", "-q", "-f", os.path.join(REPO_ROOT, "pom.xml"), "compile", "exec:java",
           "-Dexec.mainClass=project.Experiment", "-Dlog=error", "-Dexec.args=" + " ".join(java_args)]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(json_path):
        raise SystemExit("[ERROR] Penjadwalan Java gagal:\n" + proc.stdout[-2000:] + proc.stderr[-2000:])
    return load_mapping(json_path)


def load_mapping(path):
    with open(path) as f:
        return json.load(f)


def check_mapping(schedule, lengths):
    """Mapping harus cocok dengan dataset dan infrastruktur runner ini."""
    mapping = schedule["mapping"]
    if schedule["tasks"] != len(lengths) or len(mapping) != len(lengths):
        raise SystemExit(f"[ERROR] Mapping untuk {schedule['tasks']} task, dataset punya {len(lengths)} task")
    if [float(c) for c in schedule["vm_capacity"]] != [float(vm["capacity"]) for vm in VMS]:
        raise SystemExit("[ERROR] Kapasitas VM di mapping beda dengan VMS runner ini")
    if any(not 0 <= vm < len(VMS) for vm in mapping):
        raise SystemExit("[ERROR] Mapping berisi indeks VM tidak valid")


# ── Komunikasi dengan worker ──────────────────────────────────────────────────

def post_stream(url, payload, timeout=7200):
    """POST lalu baca balasan NDJSON worker baris per baris, begitu barisnya dikirim."""
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for line in resp:
            if line.strip():
                yield json.loads(line)


def where(vm):
    """Contoh: vm00 (V3) di DC-1/Host A0."""
    return f"{vm['name']} ({vm['type']}) di DC-{vm['dc']}/Host {DATACENTERS[vm['dc']]['host_type']}{vm['host']}"


def check_workers():
    for vm in VMS:
        try:
            with urllib.request.urlopen(vm["url"] + "/health", timeout=5) as resp:
                health = json.loads(resp.read())
        except OSError as exc:
            raise SystemExit(f"[ERROR] {vm['name']} ({vm['url']}) tidak merespons: {exc}\n"
                             "        Jalankan dulu: docker compose up -d")
        if not health.get("sequential"):
            raise SystemExit(f"[ERROR] {vm['name']} masih memakai worker versi lama (model paralel per-PE, "
                             "bukan antrean sekuensial).\n"
                             "        Jalankan ulang: docker compose up -d --force-recreate")
        if health.get("vm_type") != vm["type"]:
            raise SystemExit(f"[ERROR] {vm['name']} bertipe {health.get('vm_type')}, seharusnya {vm['type']}.\n"
                             "        Jalankan ulang: docker compose up -d --force-recreate")


def check_placement(path):
    """Cocokkan PLACEMENT dengan hasil CloudSim bila file-nya ada."""
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
    """Iterasi per detik setara 1,0 CPU (REF_MIPS), diukur pada VM V3 (kapasitas = REF_MIPS)."""
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
    """
    batches = {j: [] for j in range(len(VMS))}
    for task, vm in enumerate(mapping):
        batches[vm].append({"id": task, "iterations": int(lengths[task] * iters_per_mi)})

    total = len(mapping)
    finish = {}  # task -> (indeks VM, detik sejak t0)
    cpu_s = [0.0] * len(VMS)
    errors = []
    lock = threading.Lock()

    def log(t, message):
        if live:
            print(f"{t:9.2f}: Broker: {message}", flush=True)

    for task, vm in enumerate(mapping):
        log(0.0, f"Mengirim Cloudlet #{task} ({int(lengths[task])} MI) ke {where(VMS[vm])}")
    t0 = time.monotonic()

    def run_vm(j):
        vm = VMS[j]
        try:
            for event in post_stream(vm["url"] + "/batch", {"tasks": batches[j]}):
                with lock:
                    if "id" in event:
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

    threads = [threading.Thread(target=run_vm, args=(j,)) for j in batches if batches[j]]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if errors:
        raise SystemExit("[ERROR] " + "; ".join(errors))
    if len(finish) != total:
        raise SystemExit(f"[ERROR] Hanya {len(finish)} dari {total} cloudlet yang selesai")
    log(max(t for _, t in finish.values()), f"Semua cloudlet selesai ({total}/{total}). Finishing...")

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
    avg = sum(times) / len(times) if times else 0
    return (max(times) - min(times)) / avg if avg > 0 else 0.0


def compute_metrics(records, cpu_s):
    vm_finish = [0.0] * len(VMS)
    for r in records:
        j = int(r["vm"][2:])
        vm_finish[j] = max(vm_finish[j], r["finish_s"])
    makespan = max(vm_finish)

    # Energi: P(u) = P_idle + (P_max - P_idle) * u untuk 6 host dari 0 sampai Cmax.
    # integral u_k dt = CPU-detik VM di host k / kapasitas CPU host k.
    joules = 0.0
    for dc, h in HOSTS:
        host = HOST_TYPES[DATACENTERS[dc]["host_type"]]
        host_cpu = host["pes"] * host["mips"] / REF_MIPS
        busy = sum(cpu_s[j] for j, vm in enumerate(VMS) if (vm["dc"], vm["host"]) == (dc, h))
        joules += host["p_idle"] * makespan + (host["p_max"] - host["p_idle"]) * busy / host_cpu

    # Biaya: rumus yang sama dengan VmCost CloudSim (CPU + RAM + BW), VM hidup selama Cmax.
    cost = 0.0
    for vm in VMS:
        dc = DATACENTERS[vm["dc"]]
        host_mips_per_pe = HOST_TYPES[dc["host_type"]]["mips"]
        cost += dc["cost_sec"] * (vm["capacity"] / host_mips_per_pe) * makespan
        cost += dc["cost_gb_ram"] * vm["ram_gb"] + dc["cost_bw"] * vm["bw"]

    return {
        "tasks": len(records),
        "makespan_s": makespan,
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
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


METRICS = ["makespan_s", "energy_kwh", "avg_response_s", "utilization_pct", "di_all_vm",
           "di_used_vm", "throughput_task_s", "cost_usd", "scheduling_ms"]


def main():
    parser = argparse.ArgumentParser(description="Real-world runner (Docker): KPB / HS / CSA")
    parser.add_argument("--dataset", default="../src/main/resources/dataset/GoCJ_Dataset_100.txt")
    parser.add_argument("--algo", choices=["kpb", "hs", "csa"], default="kpb")
    parser.add_argument("--mapping", help="JSON mapping hasil project.Experiment --export-mapping "
                                          "(dipakai apa adanya, opsi algoritma diabaikan)")
    parser.add_argument("--k", type=float, help="KPB: persen VM kandidat (0, 100], default Java 20")
    parser.add_argument("--seed", type=int, help="HS/CSA: seed (default Java 1)")
    parser.add_argument("--evals", type=int, help="HS/CSA: jumlah evaluasi fitness (default Java 10000)")
    parser.add_argument("--w", type=float, help="bobot makespan vs energi pada fitness (default Java 0.5)")
    parser.add_argument("--hms", type=int, help="HS: harmony memory size")
    parser.add_argument("--hmcr", type=float, help="HS: harmony memory considering rate")
    parser.add_argument("--par", type=float, help="HS: pitch adjustment rate")
    parser.add_argument("--pop", type=int, help="CSA: ukuran populasi")
    parser.add_argument("--nselect", type=int, help="CSA: jumlah antibodi terbaik yang dikloning")
    parser.add_argument("--beta", type=float, help="CSA: faktor kloning")
    parser.add_argument("--mut-min", type=float, help="CSA: fraksi mutasi peringkat terbaik")
    parser.add_argument("--mut-max", type=float, help="CSA: fraksi mutasi peringkat ke-nSelect")
    parser.add_argument("--d", type=int, help="CSA: jumlah antibodi terburuk yang diganti")
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
    parser.add_argument("--out", help="folder sesi (default results/sessions/<algo>_<timestamp>)")
    parser.add_argument("--placement", default="../results/simulation/placement.csv")
    parser.add_argument("--quiet", action="store_true", help="sembunyikan log broker per cloudlet")
    args = parser.parse_args()
    if args.repeat < 1:
        raise SystemExit("--repeat minimal 1")

    if args.calibrate_only:
        check_workers()
        print(f"scale={args.iters_per_mi * REF_MIPS / calibrate():.6f} itersPerMI={args.iters_per_mi:g}")
        return

    lengths = load_gocj(args.dataset)
    dataset_name = os.path.splitext(os.path.basename(args.dataset))[0]
    out = args.out or os.path.join("results", "sessions",
                                   f"{args.algo}_{datetime.now().strftime('%Y%m%d_%H%M%S')}")
    os.makedirs(out, exist_ok=True)
    dataset_path = args.dataset
    if args.limit:
        lengths = lengths[:args.limit]
        dataset_path = os.path.join(out, f"{dataset_name}_first{args.limit}.txt")
        with open(dataset_path, "w") as f:
            f.write("\n".join(f"{x:g}" for x in lengths) + "\n")

    check_workers()
    placement_note = check_placement(args.placement)
    if args.mapping:
        schedule = load_mapping(args.mapping)
    else:
        schedule = java_schedule(args, dataset_path, os.path.join(out, f"mapping_{dataset_name}.json"))
    check_mapping(schedule, lengths)
    mapping = schedule["mapping"]
    algo, seed = schedule["algo"], schedule["seed"]

    if args.scale is not None:
        scale = args.scale  # 1 detik simulasi = scale detik nyata (dikunci lewat CLI)
        scale_note = "locked"
    else:
        rate = calibrate()
        scale = args.iters_per_mi * REF_MIPS / rate  # 1 detik simulasi = scale detik nyata
        scale_note = "calibrated"
    predicted = schedule["analytic_makespan_s"] * scale

    k_text = "" if schedule["k_percent"] is None else f"{schedule['k_percent']:g}"
    tag = (f"{dataset_name}_n{len(lengths)}_k{k_text}" if algo == "kpb"
           else f"{dataset_name}_n{len(lengths)}_{algo}_seed{seed}")
    print(f"scheduler={schedule['scheduler']} realworld, {placement_note}")
    print(f"dataset={args.dataset} tasks={len(lengths)} algo={algo} "
          f"repeat={args.repeat} itersPerMI={args.iters_per_mi:g} scale={scale:.4f} ({scale_note})")

    runs = []
    for run in range(args.first_run, args.first_run + args.repeat):
        print(f"[run {run}] menjalankan task di container... "
              f"(prediksi analitik ±{predicted:.1f} s)", flush=True)
        records, cpu_s = execute(lengths, mapping, args.iters_per_mi, live=not args.quiet)
        m = compute_metrics(records, cpu_s)
        m["scheduling_ms"] = schedule["scheduling_ms"]
        runs.append(m)

        ratio = m['makespan_s'] / predicted if predicted > 0 else float('nan')
        print(f"  makespan={m['makespan_s']:.3f}s (nyata/prediksi={ratio:.3f}x), "
              f"energy={m['energy_kwh']:.6f} kWh, "
              f"avgResponse={m['avg_response_s']:.3f}s, utilization={m['utilization_pct']:.2f}%, "
              f"DI={m['di_all_vm']:.4f} (VM terpakai {m['di_used_vm']:.4f}), "
              f"throughput={m['throughput_task_s']:.3f} task/s, cost=${m['cost_usd']:.2f}, "
              f"schedulingTime={m['scheduling_ms']:.3f} ms, vmsUsed={m['vms_used']}/20", flush=True)
        print(f"  algo={algo}, seed={'-' if seed is None else seed}, evaluations={schedule['evaluations']}, "
              f"fitness={schedule['fitness']:.6f}", flush=True)

        write_csv(os.path.join(out, f"tasks_{tag}_run{run}.csv"), records)
        row = {"timestamp": datetime.now().isoformat(timespec="seconds"), "dataset": dataset_name,
               "tasks": m["tasks"], "k_percent": k_text, "run": run,
               "iters_per_mi": args.iters_per_mi, "scale": round(scale, 5),
               "predicted_makespan_s": round(predicted, 4)}
        row.update({key: round(m[key], 6) for key in METRICS})
        row["vms_used"] = m["vms_used"]
        row.update({"algo": algo, "seed": "" if seed is None else seed,
                    "evaluations": schedule["evaluations"], "fitness": round(schedule["fitness"], 8)})
        append_csv(os.path.join(out, "summary.csv"), row)

    agg = {"dataset": dataset_name, "tasks": len(lengths), "k_percent": k_text, "runs": len(runs)}
    print(f"rata-rata ± std dari {len(runs)} run:")
    for key in METRICS:
        values = [r[key] for r in runs]
        mean = statistics.mean(values)
        std = statistics.stdev(values) if len(values) > 1 else 0.0
        agg[f"{key}_mean"], agg[f"{key}_std"] = round(mean, 6), round(std, 6)
        print(f"  {key:<18} {mean:12.4f} ± {std:.4f}")
    agg.update({"algo": algo, "seed": "" if seed is None else seed})
    append_csv(os.path.join(out, "aggregate.csv"), agg)
    print(f"hasil -> {out}/summary.csv, {out}/aggregate.csv, {out}/tasks_{tag}_run*.csv")


if __name__ == "__main__":
    main()
