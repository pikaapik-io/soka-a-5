# Soka A-5: Task Scheduling

Implementasi awal project optimasi task scheduling menggunakan Java, Maven, CloudSim Plus, dan dataset GoCJ.

## Prasyarat

- Java 11 atau lebih baru
- Maven 3.8 atau lebih baru
- Tidak perlu menjalankan Docker untuk eksperimen analitik saat ini

Cek instalasi:

```bash
java -version
mvn -version
```

## Struktur Dataset

Dataset GoCJ disimpan di:

```text
src/main/resources/dataset/
├── GoCJ_Dataset_100.txt
├── GoCJ_Dataset_500.txt
└── GoCJ_Dataset_1000.txt
```

Format file adalah satu panjang task per baris dalam satuan MI. Dataset yang tersedia berisi masing-masing 100, 500, dan 1.000 task.

Validasi jumlah baris dan rentang nilai:

```bash
for f in src/main/resources/dataset/GoCJ_Dataset_*.txt; do
  awk 'NF { n++; if ($1 < 15000 || $1 > 900000) bad++ }
       END { print FILENAME, "records=" n, "invalid=" (bad + 0) }' "$f"
done
```

Hasil yang diharapkan:

```text
GoCJ_Dataset_100.txt records=100 invalid=0
GoCJ_Dataset_500.txt records=500 invalid=0
GoCJ_Dataset_1000.txt records=1000 invalid=0
```

## Build Project

Jalankan dari root repository:

```bash
mvn -q clean compile
```

Jika build berhasil, tidak ada pesan error dari Maven.

## Menjalankan Eksperimen

### Data contoh kecil

Tanpa argumen, program memakai lima task contoh:

```bash
mvn -q exec:java
```

### Skenario S1: 100 task

```bash
mvn -q exec:java \
  -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_100.txt"
```

### Skenario S2: 500 task

```bash
mvn -q exec:java \
  -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_500.txt"
```

### Skenario S3: 1.000 task

```bash
mvn -q exec:java \
  -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_1000.txt"
```

Untuk menyimpan output ke file:

```bash
mvn -q exec:java \
  -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_1000.txt" \
  > hasil_s3.txt
```

Untuk melihat hanya ringkasan hasil:

```bash
grep -E '^(scheduler|tasks=|vmLoads=)' hasil_s3.txt
```

## Membaca Output

Contoh ringkasan:

```text
scheduler=KPB
tasks=1000, makespan=1802.667, avgResponse=35.975, throughput=0.555
mapping=[...]
vmLoads=[...]
```

Arti setiap nilai:

- `scheduler`: algoritma yang digunakan, saat ini KPB.
- `tasks`: jumlah task yang berhasil dibaca dari dataset.
- `makespan`: waktu penyelesaian seluruh task berdasarkan model analitik, dalam detik simulasi.
- `avgResponse`: rata-rata waktu penyelesaian task, dalam detik.
- `throughput`: jumlah task yang selesai per detik, dihitung dengan `tasks / makespan`.
- `mapping[i]`: indeks VM tempat task ke-i dijadwalkan.
- `vmLoads[j]`: total waktu kerja VM ke-j.

Untuk konfigurasi desain, indeks VM adalah:

```text
VM 0-3   : V3, 10.000 MIPS
VM 4-11  : V2, 3.000 MIPS
VM 12-19 : V1, 1.000 MIPS
```

## Hasil Uji Saat Ini

Hasil smoke test yang sudah dijalankan:

| Skenario | Jumlah task | Makespan | Avg response | Throughput |
|---|---:|---:|---:|---:|
| S1 | 100 | 190.500 | 36.759 | 0.525 |
| S2 | 500 | 904.700 | 36.034 | 0.553 |
| S3 | 1.000 | 1802.667 | 35.975 | 0.555 |

Hasil tersebut membuktikan bahwa dataset terbaca dan seluruh task memperoleh assignment VM. Hasil tersebut belum membuktikan KPB sebagai algoritma terbaik karena belum dibandingkan dengan Round Robin, Min-Min, atau PSO.

## Validasi Mapping

Mapping valid apabila:

1. Jumlah elemen mapping sama dengan jumlah task.
2. Setiap indeks VM berada pada rentang 0 sampai 19.
3. Tidak ada task yang hilang.
4. `vmLoads` berjumlah 20 nilai.

Pemeriksaan cepat jumlah task dari output:

```bash
grep '^tasks=' hasil_s3.txt
```

Untuk S3, output harus memuat:

```text
tasks=1000
```

## Komponen yang Sudah Tersedia

- Interface `Scheduler` dengan input task length dan kapasitas VM.
- Implementasi scheduler KPB.
- Loader dataset GoCJ.
- ETC matrix dan evaluator makespan.
- Perhitungan average response time dan throughput.
- Konfigurasi kapasitas 20 VM sesuai desain awal.
- Kerangka factory CloudSim dan setup simulasi.

## Batasan Implementasi Saat Ini

Runner yang digunakan sekarang masih berupa evaluasi analitik ringan. Oleh karena itu, output saat ini belum mencakup:

- energi CloudSim dalam kWh;
- utilisasi host;
- degree of imbalance otomatis;
- biaya eksekusi;
- ekspor CSV;
- pengulangan 10 run dengan seed berbeda;
- perbandingan Round Robin, Min-Min, dan PSO;
- real-world Docker Compose.

Untuk menyatakan hasil akhir sesuai seluruh desain project, komponen-komponen tersebut masih perlu ditambahkan dan hasilnya dibandingkan antaralgoritma.

## Perintah Ringkas

```bash
# Build
mvn -q clean compile

# Jalankan S1
mvn -q exec:java -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_100.txt"

# Jalankan S2
mvn -q exec:java -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_500.txt"

# Jalankan S3 dan simpan hasil
mvn -q exec:java \
  -Dexec.args="src/main/resources/dataset/GoCJ_Dataset_1000.txt" \
  > hasil_s3.txt
```
