#!/usr/bin/env python3
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

LOG = re.compile(r"^log_n(\d+)_k(\d+(?:\.\d+)?)_r(\d+)\.txt$")
PREDICTED = re.compile(r"prediksi analitik ±([\d.]+) s")
LINE = re.compile(
    r"makespan=(?P<makespan_s>[\d.]+)s \(nyata/prediksi=(?P<ratio>[\d.]+)x\), "
    r"energy=(?P<energy_kwh>[\d.]+) kWh, avgResponse=(?P<avg_response_s>[\d.]+)s, "
    r"utilization=(?P<utilization_pct>[\d.]+)%, DI=(?P<di>[\d.]+) \(VM terpakai (?P<di_used>[\d.]+)\), "
    r"throughput=(?P<throughput>[\d.]+) task/s, cost=\$(?P<cost>[\d.]+), "
    r"schedulingTime=(?P<scheduling_ms>[\d.]+) ms, vmsUsed=(?P<vms_used>\d+)/\d+")
METRICS = ["makespan_s", "energy_kwh", "cost", "utilization_pct", "di", "di_used", "throughput",
           "avg_response_s", "scheduling_ms", "vms_used", "ratio", "predicted_s"]
CV_LIMIT = 5.0
TIER_LIMIT = {"V3": 100.0, "V2": 30.0, "V1": 10.0}
BUSY_THRESHOLD = 2.0


def parse(path):
    with open(path) as f:
        text = f.read()
    line, predicted = LINE.search(text), PREDICTED.search(text)
    if not line or not predicted:
        raise SystemExit(f"[ERROR] {path}: no metric line / prediksi analitik")
    values = {k: float(v) for k, v in line.groupdict().items()}
    values["predicted_s"] = float(predicted.group(1))
    return values


def tier(container):
    vm = int(container.rsplit("vm", 1)[1])
    return "V3" if vm < 4 else "V2" if vm < 12 else "V1"


def cpu_by_tier(path):
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
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    session = sys.argv[1]
    runs = defaultdict(list)
    for name in sorted(os.listdir(session)):
        m = LOG.match(name)
        if m:
            runs[(int(m.group(1)), float(m.group(2)))].append(parse(os.path.join(session, name)))
    if not runs:
        raise SystemExit(f"[ERROR] no log_n<N>_k<K>_r<R>.txt in {session}")

    summary = []
    for (tasks, k), values in sorted(runs.items()):
        row = {"tasks": tasks, "k": f"{k:g}", "n": len(values)}
        for metric in METRICS:
            series = [v[metric] for v in values]
            row[metric + "_mean"] = statistics.mean(series)
            row[metric + "_std"] = statistics.stdev(series) if len(series) > 1 else 0.0
        row["cv_makespan_pct"] = 100 * row["makespan_s_std"] / row["makespan_s_mean"]
        summary.append(row)
    with open(os.path.join(session, "grid_summary.csv"), "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(summary[0]))
        writer.writeheader()
        writer.writerows(summary)

    print(f"{'task':>6} {'k':>4} {'n':>2} {'makespan (s)':>20} {'energi (kWh)':>18} {'cost ($)':>16} "
          f"{'util %':>7} {'DI':>6} {'nyata/pred':>10} {'CV %':>6}")
    for r in summary:
        flag = "  <-- CV > 5%" if r["n"] > 1 and r["cv_makespan_pct"] > CV_LIMIT else ""
        print(f"{r['tasks']:>6} {r['k']:>4} {r['n']:>2} {r['makespan_s_mean']:>11.3f} ± {r['makespan_s_std']:<6.3f} "
              f"{r['energy_kwh_mean']:>9.5f} ± {r['energy_kwh_std']:<6.5f} {r['cost_mean']:>8.2f} ± {r['cost_std']:<5.2f} "
              f"{r['utilization_pct_mean']:>7.2f} {r['di_mean']:>6.3f} {r['ratio_mean']:>10.3f} "
              f"{r['cv_makespan_pct']:>6.2f}{flag}")

    ratios = [r["ratio_mean"] for r in summary]
    print(f"\nrasio nyata/prediksi antar sel: min {min(ratios):.3f}, mean {statistics.mean(ratios):.3f}, "
          f"max {max(ratios):.3f}, CV {100 * statistics.pstdev(ratios) / statistics.mean(ratios):.2f}%")
    flagged = [f"n={r['tasks']} k={r['k']} ({r['cv_makespan_pct']:.1f}%)" for r in summary
               if r["n"] > 1 and r["cv_makespan_pct"] > CV_LIMIT]
    print("sel CV makespan > 5%: " + (", ".join(flagged) if flagged else "tidak ada")
          + ("" if any(r["n"] > 1 for r in summary) else " (1 run per sel)"))

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
