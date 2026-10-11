#!/usr/bin/env python3
"""Charts and Markdown tables for KPB grid sessions (grid_summary.csv from realworld/analyze_grid.py).

For every session given, writes into --out (default docs/figures):
  <name>_makespan.png    makespan vs task count, one line per k
  <name>_metrics.png     the eight design metrics vs task count, small multiples
  <name>_difference.png  makespan reduction vs k=20% per task count, with the capacity bound
  <name>_tables.md       Markdown tables, one per "<!-- table: ... -->" marker: metrics (rows = task
                         count x k, best per task in bold), difference (between k), theory (simple
                         capacity formulas vs CloudSim, needs the dataset files)
Numbers use ',' for thousands and '.' for decimals (1,234.56).

  pip install matplotlib
  python3 scripts/plot_grid.py gocj=results/sessions/cloudsim_gocj_<ts> synthetic=results/sessions/cloudsim_synthetic_<ts>
"""

import argparse
import csv
import os
import re
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402

# Categorical slots 1-3 of the reference palette (validated all-pairs, light surface); marker shape
# is the secondary encoding so k never depends on color alone.
SERIES = {20: ("#2a78d6", "o"), 50: ("#eb6834", "s"), 100: ("#1baf7a", "^")}
SURFACE, INK, INK_MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"

# Consistent ETC: the k% subset is always the fastest VMs, so its total capacity (MIPS) is fixed per k.
# k=20% -> 4 V3; k=50% -> 4 V3 + 6 V2; k=100% -> 4 V3 + 8 V2 + 8 V1.
SUBSET_MIPS = {20: 4 * 10_000, 50: 4 * 10_000 + 6 * 3_000, 100: 4 * 10_000 + 8 * 3_000 + 8 * 1_000}

# (column, title, unit, decimals in the table)
METRICS = [
    ("makespan_s_mean", "Makespan", "s", 2),
    ("energy_kwh_mean", "Energi", "kWh", 4),
    ("cost_mean", "Biaya", "USD", 2),
    ("avg_response_s_mean", "Avg response time", "s", 2),
    ("utilization_pct_mean", "Utilisasi", "%", 2),
    ("di_mean", "Degree of Imbalance (20 VM)", "", 3),
    ("throughput_mean", "Throughput", "task/s", 3),
    ("scheduling_ms_mean", "Waktu penjadwalan", "ms", 2),
]


def load(session):
    rows = defaultdict(dict)  # rows[k][tasks] = row
    with open(os.path.join(session, "grid_summary.csv"), newline="") as f:
        for r in csv.DictReader(f):
            rows[int(float(r["k"]))][int(r["tasks"])] = {key: float(v) for key, v in r.items()}
    return rows


def thousands(x, _=None):
    return f"{x:,.0f}"


def dataset_lengths(directory, name, n):
    if name == "gocj":
        file = "GoCJ_Dataset_50_subset.txt" if n == 50 else f"GoCJ_Dataset_{n}.txt"
    else:
        file = f"Synthetic_Dataset_{n}.txt"
    with open(os.path.join(directory, file)) as f:  # same rules as GoCJLoader.java
        return [float(t) for line in f for t in re.split(r"[,;\s]+", line.split("#", 1)[0].strip()) if t]


def style(ax, title, unit):
    ax.set_facecolor(SURFACE)
    ax.set_title(f"{title} ({unit})" if unit else title, loc="left", fontsize=10, color=INK, pad=6)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=INK_MUTED, labelsize=8, length=0)
    ax.xaxis.set_major_formatter(FuncFormatter(thousands))
    if ax.get_ylim()[1] >= 1000:
        ax.yaxis.set_major_formatter(FuncFormatter(thousands))


def draw(ax, rows, column, label_ends):
    for k, (color, marker) in SERIES.items():
        sizes = sorted(rows[k])
        values = [rows[k][n][column] for n in sizes]
        ax.plot(sizes, values, color=color, linewidth=2, marker=marker, markersize=5,
                markeredgecolor=SURFACE, markeredgewidth=1, label=f"k = {k}%")
        if label_ends:
            ax.annotate(f"k = {k}%", (sizes[-1], values[-1]), xytext=(6, 0), textcoords="offset points",
                        va="center", fontsize=8, color=INK_MUTED)


def makespan_chart(rows, label, path):
    fig, ax = plt.subplots(figsize=(8, 4.2), facecolor=SURFACE)
    draw(ax, rows, "makespan_s_mean", label_ends=True)
    style(ax, f"Makespan KPB (s) vs jumlah task, {label}", "")
    ax.set_xlabel("Jumlah task", fontsize=9, color=INK_MUTED)
    ax.legend(frameon=False, fontsize=8, loc="upper left", labelcolor=INK)
    ax.margins(x=0.08)
    fig.tight_layout()
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def metrics_chart(rows, label, path):
    fig, axes = plt.subplots(2, 4, figsize=(15, 6.6), facecolor=SURFACE)
    for ax, (column, title, unit, _) in zip(axes.flat, METRICS):
        draw(ax, rows, column, label_ends=False)
        style(ax, title, unit)
    handles, labels = axes.flat[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", ncol=3, frameon=False, fontsize=9, labelcolor=INK)
    fig.suptitle(f"Delapan metrik desain KPB, {label} (sumbu x = jumlah task)", x=0.01, ha="left",
                 fontsize=12, color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def difference_chart(rows, label, path):
    fig, ax = plt.subplots(figsize=(8, 4.2), facecolor=SURFACE)
    for k in (50, 100):
        color, marker = SERIES[k]
        sizes = sorted(rows[k])
        values = [100 * (1 - rows[k][n]["makespan_s_mean"] / rows[20][n]["makespan_s_mean"]) for n in sizes]
        ax.plot(sizes, values, color=color, linewidth=2, marker=marker, markersize=5,
                markeredgecolor=SURFACE, markeredgewidth=1, label=f"k = {k}% vs k = 20%")
        bound = 100 * (1 - SUBSET_MIPS[20] / SUBSET_MIPS[k])
        ax.axhline(bound, color=INK_MUTED, linewidth=1, linestyle=(0, (4, 3)))
        ax.annotate(f"batas kapasitas k = {k}%: {bound:.1f}%", (sizes[0], bound), xytext=(0, 4),
                    textcoords="offset points", fontsize=8, color=INK_MUTED)
    style(ax, f"Makespan lebih rendah dari k = 20% (%), {label}", "")
    ax.set_ylim(0, 50)
    ax.set_xlabel("Jumlah task", fontsize=9, color=INK_MUTED)
    ax.legend(frameon=False, fontsize=8, loc="lower right", labelcolor=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def number(x, decimals):
    return f"{x:,.{decimals}f}"


def tables(rows, label, path, lengths):
    ks = sorted(rows)
    sizes = sorted(rows[ks[0]])
    out = [f"<!-- dibuat oleh scripts/plot_grid.py, {label} -->", "", "<!-- table: metrics -->", ""]
    out.append("| Task | k | " + " | ".join(f"{t} ({u})" if u else t for _, t, u, _ in METRICS) + " |")
    out.append("|---:|:---:|" + "---:|" * len(METRICS))
    for n in sizes:
        best = {}
        for column, *_ in METRICS:
            if column != "scheduling_ms_mean":  # wall-clock noise, not a property of k
                pick = max if column in ("utilization_pct_mean", "throughput_mean") else min
                best[column] = pick(rows[k][n][column] for k in ks)
        for k in ks:
            cells = []
            for column, _, _, decimals in METRICS:
                v = rows[k][n][column]
                cells.append(f"**{number(v, decimals)}**" if best.get(column) == v else number(v, decimals))
            out.append(f"| {thousands(n)} | {k}% | " + " | ".join(cells) + " |")

    def delta(n, column, a, b):
        x, y = rows[a][n][column], rows[b][n][column]
        return f"{number(y - x, 2)} ({number(100 * (y / x - 1), 1)}%)"

    out += ["", "<!-- table: difference -->", ""]
    out.append("| Task | Makespan k = 20% → 50% (s) | Makespan k = 50% → 100% (s) | Makespan k = 20% → 100% (s) "
               "| Energi k = 20% → 100% | Biaya k = 20% → 100% | Throughput k = 20% → 100% "
               "| CloudSim / analitik f1 (k = 20 / 50 / 100%) |")
    out.append("|---:|---:|---:|---:|---:|---:|---:|:---:|")
    for n in sizes:
        pct = [number(100 * (rows[100][n][c] / rows[20][n][c] - 1), 1) + "%"
               for c in ("energy_kwh_mean", "cost_mean", "throughput_mean")]
        ratios = " / ".join(number(rows[k][n]["ratio_mean"], 3) for k in ks)
        out.append(f"| {thousands(n)} | {delta(n, 'makespan_s_mean', 20, 50)} | {delta(n, 'makespan_s_mean', 50, 100)} "
                   f"| {delta(n, 'makespan_s_mean', 20, 100)} | " + " | ".join(pct) + f" | {ratios} |")

    out += ["", "<!-- table: theory -->", ""]
    out.append("| Task | Total panjang ΣL (MI) | Rata-rata L (MI) | Makespan ΣL / C (s), k = 20 / 50 / 100% "
               "| Makespan CloudSim (s) | Throughput C / rata-rata L (task/s) | Throughput CloudSim (task/s) |")
    out.append("|---:|---:|---:|:---:|:---:|:---:|:---:|")
    for n in sizes:
        total = sum(lengths[n])
        mean = total / len(lengths[n])
        out.append(f"| {thousands(n)} | {thousands(total)} | {thousands(mean)} | "
                   + " / ".join(number(total / SUBSET_MIPS[k], 1) for k in ks) + " | "
                   + " / ".join(number(rows[k][n]["makespan_s_mean"], 1) for k in ks) + " | "
                   + " / ".join(number(SUBSET_MIPS[k] / mean, 3) for k in ks) + " | "
                   + " / ".join(number(rows[k][n]["throughput_mean"], 3) for k in ks) + " |")
    out.append("")
    with open(path, "w") as f:
        f.write("\n".join(out))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sessions", nargs="+", help="name=session_dir, e.g. gocj=results/sessions/cloudsim_gocj_...")
    parser.add_argument("--out", default="docs/figures")
    parser.add_argument("--dataset-dir", default="src/main/resources/dataset")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    labels = {"gocj": "GoCJ (CloudSim)", "synthetic": "Synthetic (CloudSim)"}
    for spec in args.sessions:
        name, session = spec.split("=", 1)
        rows = load(session)
        label = labels.get(name, name)
        makespan_chart(rows, label, os.path.join(args.out, f"{name}_makespan.png"))
        metrics_chart(rows, label, os.path.join(args.out, f"{name}_metrics.png"))
        difference_chart(rows, label, os.path.join(args.out, f"{name}_difference.png"))
        lengths = {n: dataset_lengths(args.dataset_dir, name, n) for n in rows[20]}
        tables(rows, label, os.path.join(args.out, f"{name}_tables.md"), lengths)
        print(f"{name}: {session} -> {args.out}/{name}_makespan.png, _metrics.png, _difference.png, _tables.md")


if __name__ == "__main__":
    main()
