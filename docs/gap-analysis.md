# Gap Analysis: Draft Desain Awal vs Implementasi KPB

Perbandingan **Draft Desain Awal Project Kelompok 5** dengan implementasi algoritma **KPB (K-Percent Best)** di repository ini. Angka merujuk ke [`results/simulation/summary.csv`](../results/simulation/summary.csv) dan [`realworld/results/aggregate.csv`](../realworld/results/aggregate.csv).

---

## 1. Matriks Perubahan

| Aspek | Draft awal | Implementasi | Alasan |
| :--- | :--- | :--- | :--- |
| **Algoritma** | Metaheuristik multi-objektif | **KPB (K-Percent Best)**, heuristik *immediate mode* | Tugas meminta satu algoritma *heuristic task scheduling* yang berbeda antar kelompok; LJFP sudah dipakai kelompok lain |
| **Parameter algoritma** | — | k = 20 / 50 / 100% (default 20%) | k menentukan ukuran subset VM kandidat dan tingkat konsolidasi; tiga nilai memperlihatkan efeknya |
| **Penggabungan objektif** | Weighted sum min-max, 3 konfigurasi bobot | Tidak dipakai | KPB memilih VM dengan completion time terkecil, tanpa fungsi fitness; makespan dan energi dilaporkan terpisah |
| **Jumlah run** | 10 run dengan seed berbeda | Simulasi 1×, real world 3× | Simulasi deterministik (run ulang identik, std = 0); real world 10× butuh ±1,5 jam |
| **Uji Wilcoxon** | Uji berpasangan | Tidak dipakai | Tidak ada pasangan pembanding untuk KPB, dan n = 3 terlalu kecil |
| **Java** | Java 17 | Java 11 | Lingkungan pengembangan (WSL2 Ubuntu) hanya memiliki OpenJDK 11.0.32 (/usr/lib/jvm/java-11-openjdk-amd64) |
| **Alokasi VM** | `VmAllocationPolicySimple` | `VmAllocationPolicySimple` + filter C3–C6 (`ConstrainedPlacement`) | Policy bawaan melanggar C3 (temuan 4.4) |
| **Penempatan VM ke DC** | Diserahkan ke broker | Direncanakan di awal dengan aturan yang sama | VM yang di-retry ke DC-2 tidak pernah menerima cloudlet (bagian 5) |
| **Real world** | Tidak dibahas (draft hanya mencakup simulasi CloudSim) | Sudah diimplementasikan: 20 container Docker dengan PE, CPU, RAM, jaringan DC, dan penempatan host sesuai desain | Diminta tugas poin 5–6 (implementasi dan ujicoba di real world) |

---


## 2. Gap Simulasi vs Real World

| Aspek | Simulasi (CloudSim) | Real world (Docker) | Dampak |
|:---|:---|:---|:---|
| Host fisik | 6 host terpisah | 1 laptop (Intel Core Ultra 7 155H, CPU hybrid P-core + E-core); host berupa label | Rasio waktu nyata / prediksi S3 naik dari 1,4× (16 proses PE aktif) ke 2,6× (40 proses) |
| RAM/BW 20% per task | Dimodelkan | Tidak ditiru | Meniru 1,6 GB × ratusan task mustahil di laptop 8 GB; pembanding yang adil adalah CloudSim RAM/BW 0% |
| Bandwidth dan file 300 KB | Dimodelkan | Tidak dibatasi / tidak ditransfer | Kecil, karena workload didominasi CPU |
| Energi | Integrasi utilisasi host | Model daya desain × CPU terukur dari cgroup | Estimasi, tanpa power meter / RAPL |
| Repetisi | 1× (deterministik) | 3× | Std makespan real world 2–19% dari mean |
| Tren makespan antar k | k = 100% selalu tercepat | Sama pada S2; berbalik pada S3 (k = 20% tercepat) | Kontensi CPU hybrid dan thermal throttling pada beban panjang |
