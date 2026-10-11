#!/usr/bin/env python3
"""Charts and Markdown tables for KPB grid sessions (grid_summary.csv from realworld/analyze_grid.py).

For every session given, writes into --out (default docs/figures):
  <name>_makespan.png   makespan vs task count, one line per k
  <name>_metrics.png    the eight design metrics vs task count, small multiples
  <name>_tables.md      eight-metric table (rows = task count x k, best per task in bold) and k=100% vs k=20%

  pip install matplotlib
  python3 scripts/plot_grid.py gocj=results/sessions/cloudsim_gocj_<ts> synthetic=results/sessions/cloudsim_synthetic_<ts>
"""

import argparse
import csv
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402

# Categorical slots 1-3 of the reference palette (validated all-pairs, light surface); marker shape
# is the secondary encoding so k never depends on color alone.
SERIES = {20: ("#2a78d6", "o"), 50: ("#eb6834", "s"), 100: ("#1baf7a", "^")}
SURFACE, INK, INK_MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"

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
    return f"{x:,.0f}".replace(",", ".")


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


def number(x, decimals):
    """Indonesian format: '.' thousands, ',' decimals."""
    text = f"{x:,.{decimals}f}"
    return text.replace(",", "_").replace(".", ",").replace("_", ".")


def tables(rows, label, path):
    ks = sorted(rows)
    sizes = sorted(rows[ks[0]])
    out = [f"<!-- dibuat oleh scripts/plot_grid.py, {label} -->", ""]
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
    out.append("")
    out.append("**k = 100% dibanding k = 20%**")
    out.append("")
    out.append("| Task | Makespan turun | Energi turun | Biaya turun | Throughput naik | CloudSim / analitik (k = 20 / 50 / 100%) |")
    out.append("|---:|---:|---:|---:|---:|:---:|")
    for n in sizes:
        lo, hi = rows[20][n], rows[100][n]
        drop = [100 * (1 - hi[c] / lo[c]) for c in ("makespan_s_mean", "energy_kwh_mean", "cost_mean")]
        gain = 100 * (hi["throughput_mean"] / lo["throughput_mean"] - 1)
        ratios = " / ".join(number(rows[k][n]["ratio_mean"], 3) for k in ks)
        out.append(f"| {thousands(n)} | " + " | ".join(f"{number(d, 1)}%" for d in drop)
                   + f" | {number(gain, 1)}% | {ratios} |")
    out.append("")
    with open(path, "w") as f:
        f.write("\n".join(out))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sessions", nargs="+", help="name=session_dir, e.g. gocj=results/sessions/cloudsim_gocj_...")
    parser.add_argument("--out", default="docs/figures")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    labels = {"gocj": "GoCJ (CloudSim)", "synthetic": "Synthetic (CloudSim)"}
    for spec in args.sessions:
        name, session = spec.split("=", 1)
        rows = load(session)
        label = labels.get(name, name)
        makespan_chart(rows, label, os.path.join(args.out, f"{name}_makespan.png"))
        metrics_chart(rows, label, os.path.join(args.out, f"{name}_metrics.png"))
        tables(rows, label, os.path.join(args.out, f"{name}_tables.md"))
        print(f"{name}: {session} -> {args.out}/{name}_makespan.png, {name}_metrics.png, {name}_tables.md")


if __name__ == "__main__":
    main()
