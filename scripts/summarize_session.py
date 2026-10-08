#!/usr/bin/env python3
"""Recap one session folder: mean ± std per (algo, dataset) from its summary.csv.

Works for CloudSim sessions (project.Experiment --out) and real-world sessions
(realworld/kpb_realworld.py --out). Writes recap.csv and recap.md next to summary.csv.

  python3 scripts/summarize_session.py results/sessions/cloudsim_<timestamp>
"""

import csv
import os
import statistics
import sys
from collections import OrderedDict

METRICS = [  # (column, markdown header, decimals)
    ("makespan_s", "Makespan (s)", 2),
    ("energy_kwh", "Energi (kWh)", 5),
    ("cost_usd", "Cost ($)", 2),
    ("di_all_vm", "DI", 3),
    ("utilization_pct", "Utilisasi (%)", 2),
    ("scheduling_ms", "Scheduling (ms)", 1),
    ("fitness", "Fitness", 4),
]
ALGO_ORDER = {"kpb": 0, "hs": 1, "csa": 2}


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    session = sys.argv[1]
    with open(os.path.join(session, "summary.csv"), newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        raise SystemExit("summary.csv is empty")

    groups = OrderedDict()
    for r in sorted(rows, key=lambda r: (int(r["tasks"]), ALGO_ORDER.get(r["algo"], 9), r["k_percent"])):
        label = r["algo"].upper() + (f" k={float(r['k_percent']):g}%" if r["k_percent"] else "")
        groups.setdefault((r["dataset"], int(r["tasks"]), label), []).append(r)

    recap = []
    for (dataset, tasks, label), members in groups.items():
        out = {"dataset": dataset, "tasks": tasks, "algo": label, "n": len(members),
               "seeds": " ".join(sorted({m["seed"] for m in members if m["seed"]}, key=int))}
        for column, _, _ in METRICS:
            values = [float(m[column]) for m in members]
            out[column + "_mean"] = statistics.mean(values)
            out[column + "_std"] = statistics.stdev(values) if len(values) > 1 else 0.0
        recap.append(out)

    with open(os.path.join(session, "recap.csv"), "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(recap[0]))
        writer.writeheader()
        writer.writerows(recap)

    lines = ["| Dataset | Algoritma | n | " + " | ".join(h for _, h, _ in METRICS) + " |",
             "|:---|:---|---:|" + "---:|" * len(METRICS)]
    for r in recap:
        best = min(x["makespan_s_mean"] for x in recap if x["tasks"] == r["tasks"])
        cells = []
        for column, _, decimals in METRICS:
            mean, std = r[column + "_mean"], r[column + "_std"]
            cell = f"{mean:,.{decimals}f}" + (f" ± {std:,.{decimals}f}" if r["n"] > 1 else "")
            if column == "makespan_s" and mean == best:
                cell = f"**{cell}**"
            cells.append(cell)
        lines.append(f"| {r['tasks']} | {r['algo']} | {r['n']} | " + " | ".join(cells) + " |")
    markdown = "\n".join(lines) + "\n"
    with open(os.path.join(session, "recap.md"), "w") as f:
        f.write(markdown)
    print(markdown)
    print(f"-> {session}/recap.csv, {session}/recap.md")


if __name__ == "__main__":
    main()
