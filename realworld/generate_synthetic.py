#!/usr/bin/env python3
"""Synthetic GoCJ-like datasets by bootstrap resampling.

Pools every official GoCJ_Dataset_<N>.txt in --dir (subsets such as *_subset.txt are
excluded), then draws each size with replacement from that pool. Each size has its
own generator, random.Random(seed + size), so a file never changes when other sizes
are added or removed. Writes Synthetic_Dataset_<N>.txt (one length in MI per line)
and prints per-size statistics next to the pool's, plus the Kolmogorov-Smirnov
distance to the pool.

  python3 generate_synthetic.py --dir ../src/main/resources/dataset --seed 42

Penjelasan (Bahasa Indonesia)
-----------------------------
Peran: membuat dataset uji berukuran besar (default 1.000, 2.000, ..., 10.000
task) yang distribusi panjang task-nya mirip GoCJ, karena file GoCJ resmi hanya
tersedia sampai 1.000 task. File hasilnya (Synthetic_Dataset_<N>.txt) dipakai
seperti dataset GoCJ biasa oleh simulasi CloudSim (scripts/run_grid_cloudsim.sh)
dan oleh runner real-world (run_grid.sh dengan SOURCE=synthetic). Skrip ini
dijalankan manual sekali; ia tidak dipanggil oleh skrip lain.

Input : semua file resmi GoCJ_Dataset_<N>.txt di folder --dir. File turunan
        seperti GoCJ_Dataset_50_subset.txt TIDAK ikut, karena tidak cocok dengan
        pola nama GOCJ (isinya sudah ada di GoCJ 100, jadi akan dihitung dua kali).
Output: Synthetic_Dataset_<N>.txt di folder yang sama (satu panjang MI per baris),
        plus tabel statistik di layar.

Alur:
  1. Gabungkan semua panjang task dari file GoCJ resmi menjadi satu "pool".
  2. Untuk setiap ukuran N, ambil N nilai acak DENGAN pengembalian (bootstrap)
     dari pool. Generator acak tiap ukuran adalah random.Random(seed + N), jadi
     hasilnya deterministik (selalu sama untuk seed yang sama) dan tidak
     bergantung pada ukuran lain yang ikut/tidak ikut dibuat.
  3. Tulis file; bila file sudah ada dengan isi berbeda, tolak kecuali --force.
  4. Cetak statistik (min, Q1, median, Q3, max, mean) setiap file dan pool, plus
     jarak Kolmogorov-Smirnov (KS) ke pool sebagai bukti distribusinya mirip.
"""

import argparse
import bisect
import os
import random
import re
import statistics

# Pola nama file GoCJ resmi; grup (\d+) = jumlah task. "_subset" tidak cocok pola ini.
GOCJ = re.compile(r"^GoCJ_Dataset_(\d+)\.txt$")


def load(path):
    """Baca file dataset menjadi list panjang task (int, MI).

    Aturan parsing sama dengan load_gocj() di kpb_realworld.py dan GoCJLoader.java:
    buang komentar setelah '#', pecah dengan koma/titik koma/spasi, abaikan token
    kosong. Nilai dibulatkan ke bawah menjadi int lewat int(float(...)).
    """
    with open(path) as f:
        return [int(float(tok)) for line in f for tok in re.split(r"[,;\s]+", line.split("#", 1)[0].strip()) if tok]


def ks_distance(sample, pool_sorted):
    """max |F_sample(x) - F_pool(x)| over the values of both samples.

    Jarak Kolmogorov-Smirnov dua sampel: selisih terbesar antara fungsi distribusi
    kumulatif empiris (ECDF) sampel synthetic dan ECDF pool. Nilai 0 = identik,
    1 = sama sekali tidak tumpang tindih.

    Parameter:
        sample: list panjang task hasil bootstrap.
        pool_sorted: pool yang sudah diurutkan (diurutkan sekali di main()).
    Return:
        float jarak KS.

    Alur: ECDF berbentuk tangga yang hanya berubah di titik-titik data, jadi cukup
    dievaluasi di setiap nilai unik kedua sampel. F(x) = (jumlah nilai <= x) / n,
    dihitung cepat dengan bisect_right pada list terurut.
    """
    sample_sorted = sorted(sample)
    worst = 0.0
    for x in set(sample_sorted) | set(pool_sorted):
        fs = bisect.bisect_right(sample_sorted, x) / len(sample_sorted)
        fp = bisect.bisect_right(pool_sorted, x) / len(pool_sorted)
        worst = max(worst, abs(fs - fp))
    return worst


def describe(values):
    """Ringkasan statistik satu baris: min, Q1, median, Q3, max, mean (rata kanan).

    statistics.quantiles(n=4) menghasilkan tiga titik potong kuartil [Q1, Q2, Q3];
    yang dipakai q[0] (Q1) dan q[2] (Q3), sedangkan median dihitung terpisah.
    """
    q = statistics.quantiles(values, n=4)
    return (f"{min(values):>8,} {q[0]:>9,.0f} {statistics.median(values):>9,.0f} {q[2]:>9,.0f} "
            f"{max(values):>8,} {statistics.mean(values):>10,.0f}")


def main():
    """Titik masuk: baca argumen, bangun pool GoCJ, lalu buat setiap file synthetic.

    Argumen CLI:
        --dir   folder dataset (sumber GoCJ sekaligus tujuan file synthetic).
        --seed  seed dasar generator acak (default 42); ukuran N memakai seed + N.
        --sizes daftar ukuran dipisah koma (default 1000,2000,...,10000).
        --force izinkan menimpa file yang isinya akan berubah.
    """
    parser = argparse.ArgumentParser(description="Bootstrap synthetic datasets from GoCJ")
    parser.add_argument("--dir", default="../src/main/resources/dataset")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--sizes", default=",".join(str(n) for n in range(1000, 10001, 1000)))
    parser.add_argument("--force", action="store_true", help="overwrite files whose content would change")
    args = parser.parse_args()

    # Cari file GoCJ resmi, urutkan menurut jumlah task (N), lalu gabungkan isinya.
    sources = sorted((int(m.group(1)), name) for name in os.listdir(args.dir) if (m := GOCJ.match(name)))
    if not sources:
        raise SystemExit(f"[ERROR] no GoCJ_Dataset_<N>.txt in {args.dir}")
    pool = [x for _, name in sources for x in load(os.path.join(args.dir, name))]
    pool_sorted = sorted(pool)
    # Cetak header tabel dan baris statistik pool sebagai pembanding.
    print(f"pool: {len(pool)} task dari {len(sources)} file ({', '.join(name for _, name in sources)}), seed={args.seed}")
    print(f"{'dataset':<26} {'n':>6} {'min':>8} {'Q1':>9} {'median':>9} {'Q3':>9} {'max':>8} {'mean':>10} {'KS':>6}")
    print(f"{'GoCJ gabungan (pool)':<26} {len(pool):>6} {describe(pool)} {'-':>6}")

    for size in (int(s) for s in args.sizes.split(",")):
        # Bootstrap: choices() mengambil `size` elemen acak dengan pengembalian.
        sample = random.Random(args.seed + size).choices(pool, k=size)
        path = os.path.join(args.dir, f"Synthetic_Dataset_{size}.txt")
        content = "\n".join(str(x) for x in sample) + "\n"
        # Pengaman: jangan diam-diam mengubah dataset yang sudah dipakai eksperimen.
        # Bila isinya identik, file ditulis ulang tanpa masalah.
        if os.path.exists(path):
            with open(path) as f:
                if f.read() != content and not args.force:
                    raise SystemExit(f"[ERROR] {path} exists with different content; use --force to overwrite")
        with open(path, "w") as f:
            f.write(content)
        print(f"{os.path.basename(path):<26} {size:>6} {describe(sample)} {ks_distance(sample, pool_sorted):>6.3f}")


if __name__ == "__main__":
    main()
