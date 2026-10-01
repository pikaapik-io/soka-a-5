# Gap Analysis: Draft Desain Awal vs Implementasi KPB

Perbandingan **Draft Desain Awal Project Kelompok 5** dengan implementasi algoritma **KPB (K-Percent Best)** di repository ini. Angka merujuk ke [`results/simulation/summary.csv`](../results/simulation/summary.csv) dan [`realworld/results/aggregate.csv`](../realworld/results/aggregate.csv).

---

## 1. Ringkasan Kesesuaian

| Bagian desain | Status | Keterangan |
|:---|:---:|:---|
| 1.1 Workload bag-of-tasks, independen, non-preemptive | ✅ | Sesuai |
| 1.2 Dataset GoCJ 100 / 500 / 1.000 | ✅ | Sesuai |
| 1.3 Parameter cloudlet (1 PE, 300 KB, Full / Dynamic 20%, t = 0) | ✅ | Sesuai di simulasi; real world tidak meniru RAM/BW 20% |
| 1.4 Skenario S1, S2, S3 | ✅ | Sesuai, masing-masing dengan k = 20 / 50 / 100% |
| 2.1–2.2 Dua datacenter, policy, scheduler, biaya | ✅ | Sesuai, plus filter batasan C3–C6 pada `VmAllocationPolicySimple` |
| 2.3 Host Tipe A / B dan model daya linear | ✅ | Sesuai |
| 2.4 20 VM V1 / V2 / V3, V3 hanya di Tipe A | ✅ | Sesuai; penempatan CloudSim: DC-1 = 4 V3 + 4 V2, DC-2 = 4 V2 + 8 V1 |
| 3.1–3.2 Objektif makespan dan energi | ✅ | Diukur di simulasi dan real world |
| 3.4 Weighted sum (w1, w2) | ➖ | Tidak dipakai: KPB heuristik tanpa fungsi fitness |
| 3.5 Algoritma | ⚠️ | Algoritma usulan metaheuristik diganti KPB (heuristik) |
| 4.1 Delapan metrik | ✅ | Kedelapan metrik dihitung dan diekspor ke CSV |
| 4.2 Protokol 10 run, mean ± std, CSV | ⚠️ | Simulasi 1× (deterministik), real world 3× dengan mean ± std |
| 4.3 Batasan keras C1–C8 | ✅ | Ditegakkan dan divalidasi setiap run |
| 4.4 Batasan lunak (deadline, utilisasi host 85%) | ❌ | Belum diimplementasikan |
| 4.5 Asumsi penyederhanaan | ✅ | Tetap berlaku |
| 5.1 Lingkungan: CloudSim Plus, Java 17, Maven, Python | ⚠️ | Java 11 (alasan di bagian 3) |
| 5.4 Tiga risiko teknis | ✅ | Ketiganya ditangani (bagian 5) |

---

## 2. Matriks Perubahan

| Aspek | Draft awal | Implementasi | Alasan |
| :--- | :--- | :--- | :--- |
| **Algoritma** | Metaheuristik multi-objektif | **KPB (K-Percent Best)**, heuristik *immediate mode* | Tugas meminta satu algoritma *heuristic task scheduling* yang berbeda antar kelompok; LJFP sudah dipakai kelompok lain |
| **Parameter algoritma** | — | k = 20 / 50 / 100% (default 20%) | k menentukan ukuran subset VM kandidat dan tingkat konsolidasi; tiga nilai memperlihatkan efeknya |
| **Penggabungan objektif** | Weighted sum min-max, 3 konfigurasi bobot | Tidak dipakai | KPB memilih VM dengan completion time terkecil, tanpa fungsi fitness; makespan dan energi dilaporkan terpisah |
| **Jumlah run** | 10 run dengan seed berbeda | Simulasi 1×, real world 3× | Simulasi deterministik (run ulang identik, std = 0); real world 10× butuh ±1,5 jam |
| **Uji Wilcoxon** | Uji berpasangan | Tidak dipakai | Tidak ada pasangan pembanding untuk KPB, dan n = 3 terlalu kecil |
| **Java** | Java 17 | Java 11 | Dijabarkan di bagian 3 |
| **Alokasi VM** | `VmAllocationPolicySimple` | `VmAllocationPolicySimple` + filter C3–C6 (`ConstrainedPlacement`) | Policy bawaan melanggar C3 (temuan 4.4) |
| **Penempatan VM ke DC** | Diserahkan ke broker | Direncanakan di awal dengan aturan yang sama | VM yang di-retry ke DC-2 tidak pernah menerima cloudlet (bagian 5) |
| **Real world** | Belum dirancang | 20 container Docker dengan PE, CPU, RAM, jaringan DC, dan penempatan host sesuai desain | Tugas poin 5–6 |

---

## 3. Alasan Java 11, Bukan Java 17

| Faktor | Keterangan |
|:---|:---|
| JDK yang tersedia | Lingkungan pengembangan (WSL2 Ubuntu) hanya memiliki OpenJDK 11.0.32 (`/usr/lib/jvm/java-11-openjdk-amd64`); JDK 17 tidak terpasang |
| Kebutuhan CloudSim Plus | Proyek memakai CloudSim Plus **6.6.1**, yang dikompilasi untuk Java 8 (class file version 52), sehingga berjalan di Java 11 tanpa masalah |
| Kapan Java 17 wajib | Java 17 baru diwajibkan mulai CloudSim Plus **7.0.0** (`<release>17</release>`; versi 8.0.0 memakai class file version 61) |
| Biaya upgrade ke CloudSim Plus 7/8 | Nama package berubah dari `org.cloudbus.cloudsim.*` ke `org.cloudsimplus.*`, sehingga semua import dan sebagian API harus ditulis ulang tanpa menambah fitur yang dibutuhkan desain |
| Fitur yang dibutuhkan desain | Semua komponen desain (2 DC, `PowerModelHostSimple`, `VmCost`, `VmAllocationPolicySimple`, `CloudletSchedulerTimeShared`, `UtilizationModelDynamic`) sudah tersedia di 6.6.1 |
| Fitur bahasa | Kode tidak memakai fitur Java 12+ (record, text block, switch expression, pattern matching), sehingga tidak ada bagian yang membutuhkan Java 17 |
| Kompatibilitas ke depan | `pom.xml` memakai `maven.compiler.release` 11; kode ini dapat dikompilasi oleh JDK 17 tanpa perubahan (belum diuji karena JDK 17 tidak tersedia) |
| Konsistensi tim | Semua anggota cukup memasang JDK 11+, yang tersedia di repositori paket Ubuntu standar tanpa konfigurasi tambahan |

---

## 4. Bagian Desain yang Kurang Tepat untuk Diterapkan

| No | Temuan pada desain | Bukti | Dampak | Rekomendasi |
|:---:|:---|:---|:---|:---|
| 4.1 | Rumus makespan f1 = max Σ L_i / (MIPS_j × PE_j) menganggap satu task memakai **semua** PE VM, padahal desain menetapkan cloudlet 1 PE | Contoh 5 task: f1 = 90 s, CloudSim = **360,21 s** (900.000 MI ÷ 2.500 MIPS per PE). Pada 9 skenario, CloudSim tanpa kontensi RAM/BW 4–83% lebih lambat dari f1 | Estimasi KPB menilai V3 sebagai 10.000 MIPS untuk satu task, padahal efektif 2.500 MIPS | ETC = L_i / MIPS per PE, atau set PE cloudlet = PE VM |
| 4.2 | RAM/BW 20% per cloudlet dengan `CloudletSchedulerTimeShared`: semua cloudlet di satu VM aktif bersamaan, dan RAM VM habis setelah 5 cloudlet | Makespan RAM/BW 20% vs 0%: S1 k=20% 1.327 vs 514 s (2,6×); S2 k=20% 21.269 vs 1.706 s (12,5×); S3 k=20% 87.658 vs 3.422 s (**25,6×**); S3 k=100% 15.060 vs 1.969 s (7,6×) | Hasil lebih ditentukan oleh model virtual memory CloudSim daripada oleh keputusan KPB; konsolidasi (k kecil) sangat dirugikan | `CloudletSchedulerSpaceShared`, atau utilisasi RAM/BW absolut kecil (mis. 50 MB per cloudlet) |
| 4.3 | Energi dihitung untuk **semua** host dari 0 sampai Cmax (bagian 3.2), padahal bagian 3.3 menyatakan konsolidasi menghemat energi | 6 host selalu menyala (daya idle 741 W). Energi k=20% vs k=100%: S1 0,30 vs 0,11 kWh; S2 5,08 vs 1,05 kWh; S3 20,79 vs 3,82 kWh | Trade-off makespan–energi tidak pernah muncul; konfigurasi tercepat selalu paling hemat energi | Izinkan host tanpa VM aktif dimatikan (`Host.setIdleShutdownDeadline()`), atau hitung energi host hanya sampai cloudlet terakhirnya selesai |
| 4.4 | `VmAllocationPolicySimple` + `VmSchedulerTimeShared` dianggap cukup untuk batasan C3 (Σ PE VM ≤ PE host) | Kombinasi bawaan hanya mengecek MIPS: 20 VM (72.000 MIPS) masuk semua ke DC-1, satu host berisi 11 PE dari 8, DC-2 kosong | Desain dua datacenter tidak terwujud | Filter host yang menegakkan C3–C6 (sudah diterapkan: `ConstrainedPlacement`) |
| 4.5 | Rasio beban "ruang bebas sekitar 37%" dihitung dalam MIPS | Dalam PE, VM meminta 40 dari 48 PE (83%); DC-1 penuh 24/24 PE, DC-2 16/24 PE | Ruang bebas sebenarnya 17% dan seluruhnya di DC-2 | Hitung rasio beban per PE, RAM, dan MIPS; yang paling ketat menentukan |
| 4.6 | Protokol 10 run dengan seed acak | Dataset, waktu tiba, KPB, dan CloudSim semuanya deterministik; dua run simulasi identik | 10 run simulasi memberi std = 0 | Seed hanya bermakna bila ada unsur acak, mis. urutan kedatangan diacak per seed |
| 4.7 | Batasan lunak: deadline 1,5 × (L_i / MIPS rata-rata) dan utilisasi host ≤ 85%, sebagai penalti fitness | "MIPS rata-rata" ambigu (kapasitas VM rata-rata 3.600 MIPS, MIPS per PE, atau MIPS host); KPB tidak punya fungsi fitness | Tidak dapat diterapkan apa adanya pada KPB | Definisikan MIPS rata-rata, lalu laporkan jumlah pelanggaran deadline sebagai metrik |
| 4.8 | Cost per bandwidth $0,005 / $0,003 tanpa satuan | `VmCost` membebankan BW sekali per kapasitas VM (Mbps); biaya CPU = costPerSecond × (MIPS VM ÷ MIPS per PE host) × umur VM, sehingga V3 di host A dikenai 3,3× tarif per detik | Biaya total hampir sepenuhnya ditentukan makespan | Tuliskan satuan dan formula biaya, atau nyatakan memakai formula `VmCost` CloudSim |

---

## 5. Status Risiko Teknis

| Risiko | Sumber | Status | Penanganan |
|:---|:---:|:---:|:---|
| Fitness memanggil simulasi penuh | Desain 5.4 | ✅ Tidak relevan | KPB tidak memakai fitness; simulasi dijalankan sekali per konfigurasi |
| Akumulasi energi di CloudSim Plus | Desain 5.4 | ✅ Ditangani | `EnergyMeter` mengintegrasikan P(u) tiap clock tick (interval 1 s), termasuk host idle; selisih dengan perhitungan eksak < 0,1% |
| Kegagalan alokasi VM yang senyap | Desain 5.4 | ✅ Ditangani | Validasi 20/20 VM dibuat dan semua cloudlet selesai |
| `bindCloudletToVm()` diam-diam gagal | Ditemukan saat implementasi | ✅ Ditangani | Mengembalikan `false` bila dipanggil sebelum `submitCloudletList()`, sehingga broker memakai round-robin; binding dipindah setelah submit dan tiap hasilnya dicek |
| Cloudlet VM hasil retry tidak dikirim | Ditemukan saat implementasi | ✅ Ditangani | VM yang di-retry dari DC-1 ke DC-2 dibuat, tetapi 43 dari 100 cloudlet tertinggal QUEUED; penempatan VM → DC kini direncanakan di awal |

---

## 6. Gap Simulasi vs Real World

| Aspek | Simulasi (CloudSim) | Real world (Docker) | Dampak |
|:---|:---|:---|:---|
| Host fisik | 6 host terpisah | 1 laptop (Intel Core Ultra 7 155H, CPU hybrid P-core + E-core); host berupa label | Rasio waktu nyata / prediksi S3 naik dari 1,4× (16 proses PE aktif) ke 2,6× (40 proses) |
| RAM/BW 20% per task | Dimodelkan | Tidak ditiru | Meniru 1,6 GB × ratusan task mustahil di laptop 8 GB; pembanding yang adil adalah CloudSim RAM/BW 0% |
| Bandwidth dan file 300 KB | Dimodelkan | Tidak dibatasi / tidak ditransfer | Kecil, karena workload didominasi CPU |
| Energi | Integrasi utilisasi host | Model daya desain × CPU terukur dari cgroup | Estimasi, tanpa power meter / RAPL |
| Repetisi | 1× (deterministik) | 3× | Std makespan real world 2–19% dari mean |
| Tren makespan antar k | k = 100% selalu tercepat | Sama pada S2; berbalik pada S3 (k = 20% tercepat) | Kontensi CPU hybrid dan thermal throttling pada beban panjang |
