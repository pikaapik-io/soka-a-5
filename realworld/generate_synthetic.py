#!/usr/bin/env python3
"""Synthetic GoCJ-like datasets by bootstrap resampling.

Pools every official GoCJ_Dataset_<N>.txt in --dir (subsets such as *_subset.txt are
excluded), then draws each size with replacement from that pool. Each size has its
own generator, random.Random(seed + size), so a file never changes when other sizes
are added or removed. Writes Synthetic_Dataset_<N>.txt (one length in MI per line)
and prints per-size statistics next to the pool's, plus the Kolmogorov-Smirnov
distance to the pool.

  python3 generate_synthetic.py --dir ../src/main/resources/dataset --seed 42
"""

import argparse
import bisect
import os
import random
import re
import statistics

GOCJ = re.compile(r"^GoCJ_Dataset_(\d+)\.txt$")


def load(path):
    with open(path) as f:
        return [int(float(tok)) for line in f for tok in re.split(r"[,;\s]+", line.split("#", 1)[0].strip()) if tok]


def ks_distance(sample, pool_sorted):
    """max |F_sample(x) - F_pool(x)| over the values of both samples."""
    sample_sorted = sorted(sample)
    worst = 0.0
    for x in set(sample_sorted) | set(pool_sorted):
        fs = bisect.bisect_right(sample_sorted, x) / len(sample_sorted)
        fp = bisect.bisect_right(pool_sorted, x) / len(pool_sorted)
        worst = max(worst, abs(fs - fp))
    return worst


def describe(values):
    q = statistics.quantiles(values, n=4)
    return (f"{min(values):>8,} {q[0]:>9,.0f} {statistics.median(values):>9,.0f} {q[2]:>9,.0f} "
            f"{max(values):>8,} {statistics.mean(values):>10,.0f}")


def main():
    parser = argparse.ArgumentParser(description="Bootstrap synthetic datasets from GoCJ")
    parser.add_argument("--dir", default="../src/main/resources/dataset")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--sizes", default=",".join(str(n) for n in range(1000, 10001, 1000)))
    parser.add_argument("--force", action="store_true", help="overwrite files whose content would change")
    args = parser.parse_args()

    sources = sorted((int(m.group(1)), name) for name in os.listdir(args.dir) if (m := GOCJ.match(name)))
    if not sources:
        raise SystemExit(f"[ERROR] no GoCJ_Dataset_<N>.txt in {args.dir}")
    pool = [x for _, name in sources for x in load(os.path.join(args.dir, name))]
    pool_sorted = sorted(pool)
    print(f"pool: {len(pool)} task dari {len(sources)} file ({', '.join(name for _, name in sources)}), seed={args.seed}")
    print(f"{'dataset':<26} {'n':>6} {'min':>8} {'Q1':>9} {'median':>9} {'Q3':>9} {'max':>8} {'mean':>10} {'KS':>6}")
    print(f"{'GoCJ gabungan (pool)':<26} {len(pool):>6} {describe(pool)} {'-':>6}")

    for size in (int(s) for s in args.sizes.split(",")):
        sample = random.Random(args.seed + size).choices(pool, k=size)
        path = os.path.join(args.dir, f"Synthetic_Dataset_{size}.txt")
        content = "\n".join(str(x) for x in sample) + "\n"
        if os.path.exists(path):
            with open(path) as f:
                if f.read() != content and not args.force:
                    raise SystemExit(f"[ERROR] {path} exists with different content; use --force to overwrite")
        with open(path, "w") as f:
            f.write(content)
        print(f"{os.path.basename(path):<26} {size:>6} {describe(sample)} {ks_distance(sample, pool_sorted):>6.3f}")


if __name__ == "__main__":
    main()
