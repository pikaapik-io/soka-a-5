#!/usr/bin/env python3
"""Charts and Markdown tables for KPB grid sessions (grid_summary.csv from realworld/analyze_grid.py).

Charts (into --out, default docs/figures): one PNG per metric, one panel per session side by side
(e.g. GoCJ left, Synthetic right), no title inside the image; x = number of tasks, y = the metric
with its unit, one line per k. The README puts the caption and the short analysis under each image.
  makespan.png, selisih_makespan.png, energi.png, biaya.png, avg_response.png, utilisasi.png,
  di.png, throughput.png, waktu_penjadwalan.png

Tables (per session): <name>_tables.md, one Markdown table per "<!-- table: ... -->" marker:
metrics (rows = task count x k, best per task in bold), difference (between k), theory (simple
capacity formulas vs CloudSim, needs the dataset files).
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


# (file, column, y-axis label)
FIGURES = [
    ("makespan", "makespan_s_mean", "Makespan (detik)"),
    ("energi", "energy_kwh_mean", "Energi (kWh)"),
    ("biaya", "cost_mean", "Biaya (USD)"),
    ("avg_response", "avg_response_s_mean", "Avg response time (detik)"),
    ("utilisasi", "utilization_pct_mean", "Utilisasi VM (%)"),
    ("di", "di_mean", "Degree of Imbalance"),
    ("throughput", "throughput_mean", "Throughput (task/detik)"),
    ("waktu_penjadwalan", "scheduling_ms_mean", "Waktu penjadwalan (ms)"),
]
SOURCE_LABEL = {"gocj": "GoCJ", "synthetic": "Synthetic"}


def style(ax, source, ylabel):
    ax.set_facecolor(SURFACE)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=INK_MUTED, labelsize=9, length=0)
    ax.set_xlabel(f"Jumlah task ({SOURCE_LABEL.get(source, source)})", fontsize=10, color=INK)
    ax.set_ylabel(ylabel, fontsize=10, color=INK)
    ax.xaxis.set_major_formatter(FuncFormatter(thousands))
    if ax.get_ylim()[1] >= 1000:
        ax.yaxis.set_major_formatter(FuncFormatter(thousands))


def lines(ax, rows, ks, value):
    for k in ks:
        color, marker = SERIES[k]
        sizes = sorted(rows[k])
        ax.plot(sizes, [value(k, n) for n in sizes], color=color, linewidth=2, marker=marker, markersize=6,
                markeredgecolor=SURFACE, markeredgewidth=1, label=f"k = {k}%")


def figure(sessions, path, ylabel, ks, value, decorate=None):
    fig, axes = plt.subplots(1, len(sessions), figsize=(5.2 * len(sessions), 3.9), facecolor=SURFACE,
                             squeeze=False)
    for ax, (source, rows) in zip(axes[0], sessions):
        lines(ax, rows, ks, lambda k, n, rows=rows: value(rows, k, n))
        if decorate:
            decorate(ax, rows)
        style(ax, source, ylabel)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=len(ks), frameon=False, fontsize=10, labelcolor=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.9))
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def charts(sessions, out):
    for name, column, ylabel in FIGURES:
        figure(sessions, os.path.join(out, f"{name}.png"), ylabel, sorted(SERIES),
               lambda rows, k, n, column=column: rows[k][n][column])

    def bounds(ax, rows):
        for k in (50, 100):
            bound = 100 * (1 - SUBSET_MIPS[20] / SUBSET_MIPS[k])
            ax.axhline(bound, color=INK_MUTED, linewidth=1, linestyle=(0, (4, 3)))
            ax.annotate(f"batas teori k = {k}%: {bound:.1f}%", (min(rows[k]), bound), xytext=(0, 4),
                        textcoords="offset points", fontsize=8, color=INK_MUTED)
        ax.set_ylim(0, 50)

    figure(sessions, os.path.join(out, "selisih_makespan.png"), "Makespan lebih rendah dari k = 20% (%)",
           [50, 100], lambda rows, k, n: 100 * (1 - rows[k][n]["makespan_s_mean"] / rows[20][n]["makespan_s_mean"]),
           decorate=bounds)


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
    sessions = []
    for spec in args.sessions:
        name, session = spec.split("=", 1)
        rows = load(session)
        sessions.append((name, rows))
        lengths = {n: dataset_lengths(args.dataset_dir, name, n) for n in rows[20]}
        tables(rows, labels.get(name, name), os.path.join(args.out, f"{name}_tables.md"), lengths)
        print(f"{name}: {session} -> {args.out}/{name}_tables.md")
    charts(sessions, args.out)
    print(f"grafik -> {args.out}/" + ", ".join(f"{n}.png" for n, _, _ in FIGURES) + ", selisih_makespan.png")


if __name__ == "__main__":
    main()
