#!/usr/bin/env python3
"""Validate one real-world session: per-cell makespan CV, real/predicted ratio, and busy CPU% per tier.

  python3 scripts/validate_realworld_session.py realworld/results/sessions/experiment_<timestamp>

Writes validation.md next to summary.csv. A cell = (tasks, algo, k, seed); it is flagged
when the coefficient of variation of its makespan over the repetitions exceeds 5%.
"""

import csv
import os
import statistics
import sys
from collections import OrderedDict, defaultdict

CV_LIMIT = 5.0
TIER_LIMIT = {"V3": 100.0, "V2": 30.0, "V1": 10.0}  # container cpu_quota in % of one core
BUSY_THRESHOLD = 2.0  # samples below this CPU% count as idle


def tier(container):
    vm = int(container.rsplit("vm", 1)[1])
    return "V3" if vm < 4 else "V2" if vm < 12 else "V1"


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    session = sys.argv[1]
    with open(os.path.join(session, "summary.csv"), newline="") as f:
        rows = list(csv.DictReader(f))

    cells = OrderedDict()
    for r in sorted(rows, key=lambda r: (int(r["tasks"]), r["algo"], r["k_percent"], r["seed"])):
        label = r["algo"].upper() + (f" k={float(r['k_percent']):g}%" if r["k_percent"] else f" seed {r['seed']}")
        cells.setdefault((int(r["tasks"]), label), []).append(r)

    lines = ["## Validasi sesi real-world", "",
             "| Task | Sel | n | Makespan (s) | CV (%) | Nyata / prediksi | Status |",
             "|---:|:---|---:|---:|---:|---:|:---|"]
    flagged, ratios = [], defaultdict(list)
    for (tasks, label), members in cells.items():
        ms = [float(m["makespan_s"]) for m in members]
        ratio = [float(m["makespan_s"]) / float(m["predicted_makespan_s"]) for m in members]
        mean = statistics.mean(ms)
        cv = 100 * statistics.stdev(ms) / mean if len(ms) > 1 else 0.0
        ratios[tasks] += ratio
        status = "ok" if cv <= CV_LIMIT else "**CV > 5%**"
        if cv > CV_LIMIT:
            flagged.append(f"{tasks} task, {label} (CV {cv:.1f}%)")
        lines.append(f"| {tasks} | {label} | {len(ms)} | {mean:.3f} | {cv:.2f} | "
                     f"{statistics.mean(ratio):.3f} | {status} |")

    lines += ["", "| Task | Rasio nyata/prediksi min | mean | max |", "|---:|---:|---:|---:|"]
    for tasks, values in ratios.items():
        lines.append(f"| {tasks} | {min(values):.3f} | {statistics.mean(values):.3f} | {max(values):.3f} |")

    # Drift: the same locked scale should give a flat ratio over time; a step means the machine sped up or slowed down.
    timeline = sorted(rows, key=lambda r: r["timestamp"])
    timeline_ratio = [float(r["makespan_s"]) / float(r["predicted_makespan_s"]) for r in timeline]
    lines += ["", "Drift waktu (urutan eksekusi, blok 10 run):", "",
              "| Run | Waktu selesai | Rasio mean | SD | Min | Max |", "|:---|:---|---:|---:|---:|---:|"]
    for a in range(0, len(timeline), 10):
        block = timeline_ratio[a:a + 10]
        sd = statistics.stdev(block) if len(block) > 1 else 0.0
        lines.append(f"| {a + 1}–{a + len(block)} | {timeline[a]['timestamp'][11:]}–{timeline[a + len(block) - 1]['timestamp'][11:]} "
                     f"| {statistics.mean(block):.3f} | {sd:.3f} | {min(block):.2f} | {max(block):.2f} |")

    stats_path = os.path.join(session, "docker_stats.csv")
    if os.path.exists(stats_path):
        busy = defaultdict(list)
        with open(stats_path, newline="") as f:
            for r in csv.DictReader(f):
                try:
                    cpu = float(r["cpu_pct"].rstrip("%"))
                except ValueError:
                    continue
                if "kpb_vm" in r["container"] and cpu >= BUSY_THRESHOLD:
                    busy[tier(r["container"])].append(cpu)
        lines += ["", f"CPU% container saat sibuk (sampel ≥ {BUSY_THRESHOLD:g}%, docker stats):", "",
                  "| Tier | Limit (%) | Sampel | Median (%) | P90 (%) | Median / limit |",
                  "|:---|---:|---:|---:|---:|---:|"]
        for t in ("V3", "V2", "V1"):
            values = sorted(busy[t])
            if not values:
                lines.append(f"| {t} | {TIER_LIMIT[t]:g} | 0 | — | — | — |")
                continue
            median = statistics.median(values)
            p90 = values[int(0.9 * (len(values) - 1))]
            lines.append(f"| {t} | {TIER_LIMIT[t]:g} | {len(values)} | {median:.1f} | {p90:.1f} | "
                         f"{median / TIER_LIMIT[t]:.2f} |")

    lines += ["", "Sel dengan CV > 5%: " + ("; ".join(flagged) if flagged else "tidak ada")]
    text = "\n".join(lines) + "\n"
    with open(os.path.join(session, "validation.md"), "w") as f:
        f.write(text)
    print(text)
    print(f"-> {session}/validation.md")


if __name__ == "__main__":
    main()
