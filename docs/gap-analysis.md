# Gap Analysis: Draft Desain Awal vs Implementasi

Dokumen ini membandingkan **Draft Desain Awal Project Kelompok 5** (*Optimasi Task Scheduling dan Resource Allocation pada Lingkungan Cloud Computing*) dengan implementasi di repository ini. Dokumen ini mencatat:

1. bagian desain yang sudah diterapkan apa adanya;
2. bagian yang sengaja diubah, beserta alasannya;
3. bagian desain yang ternyata **kurang tepat untuk diterapkan**, dengan bukti dari hasil simulasi dan real world, serta rekomendasi revisinya.

Semua angka merujuk ke [`results/simulation/summary.csv`](../results/simulation/summary.csv) dan [`realworld/results/aggregate.csv`](../realworld/results/aggregate.csv).

---

## 1. Ringkasan Kesesuaian

| Bagian desain | Status | Keterangan |
|:---|:---:|:---|
| 1.1 Workload bag-of-tasks, independen, non-preemptive | ✅ | Sesuai |
| 1.2 Dataset GoCJ 100 / 500 / 1.000 | ✅ | Sesuai |
| 1.3 Parameter cloudlet (1 PE, 300 KB, Full / Dynamic 20%, t = 0) | ✅ | Sesuai di simulasi. Real world tidak meniru RAM/BW 20% (lihat 5) |
| 1.4 Skenario S1, S2, S3 | ✅ | Sesuai, masing-masing dengan k = 20 / 50 / 100% |
| 2.1–2.2 Dua datacenter, policy, scheduler, biaya | ✅ | Sesuai, plus filter batasan C3–C6 pada `VmAllocationPolicySimple` (lihat 3.4) |
| 2.3 Host Tipe A / B dan model daya linear | ✅ | Sesuai |
| 2.4 20 VM V1 / V2 / V3, V3 hanya di Tipe A | ✅ | Sesuai. Penempatan hasil CloudSim: DC-1 = 4 V3 + 4 V2, DC-2 = 4 V2 + 8 V1 |
| 3.1–3.2 Objektif makespan dan energi | ✅ | Diukur di simulasi dan real world |
| 3.4 Weighted sum (w1, w2) | ➖ | Tidak dipakai: KPB adalah heuristik tanpa fungsi fitness |
| 3.5 Algoritma usulan + RR, Min-Min, PSO | ⚠️ | Hanya KPB (tugas minggu ini satu algoritma) |
| 4.1 Delapan metrik | ✅ | Kedelapan metrik dihitung dan diekspor ke CSV |
| 4.2 Protokol 10 run, mean ± std, Wilcoxon, CSV | ⚠️ | Simulasi 1× (deterministik), real world 3× dengan mean ± std, tanpa Wilcoxon |
| 4.3 Batasan keras C1–C8 | ✅ | Ditegakkan dan divalidasi setiap run |
| 4.4 Batasan lunak (deadline, utilisasi host 85%) | ❌ | Belum diimplementasikan (lihat 3.7) |
| 4.5 Asumsi penyederhanaan | ✅ | Tetap berlaku |
| 5.1 Lingkungan: CloudSim Plus, Java 17, Maven, Python | ⚠️ | Java 11 (JDK yang terpasang) |
| 5.4 Tiga risiko teknis | ✅ | Ketiganya ditangani (lihat 4) |

---

## 2. Matriks Perubahan yang Disengaja

| Aspek | Draft awal | Implementasi | Alasan |
| :--- | :--- | :--- | :--- |
| **Algoritma usulan** | Metaheuristik multi-objektif | **KPB (K-Percent Best)**, heuristik | Tugas meminta 1 algoritma *heuristic task scheduling* yang tidak sama antar kelompok |
| **Algoritma pembanding** | Round Robin, Min-Min, PSO | Tidak ada; sebagai gantinya parameter **k = 20 / 50 / 100%** dibandingkan | Tugas minggu ini satu algoritma. k = 100% setara MCT, sehingga perbandingan antar k tetap menunjukkan efek parameter |
| **Penggabungan objektif** | Weighted sum min-max, 3 konfigurasi bobot | Tidak dipakai | KPB tidak memakai fungsi fitness. Makespan dan energi tetap dilaporkan terpisah |
| **Jumlah run** | 10 run dengan seed berbeda | Simulasi 1×, real world 3× | Simulasi terbukti deterministik (run ulang identik), sehingga 10 run memberi std = 0. Real world 10× butuh ±1,5 jam |
| **Uji Wilcoxon** | Perbandingan berpasangan antar algoritma | Tidak dipakai | Hanya satu algoritma; n = 3 juga terlalu kecil untuk Wilcoxon |
| **Java** | Java 17 | Java 11 | JDK yang tersedia di lingkungan pengembangan. Kode tidak memakai fitur di atas Java 11 |
| **Alokasi VM** | `VmAllocationPolicySimple` | `VmAllocationPolicySimple` + filter C3–C6 (`ConstrainedPlacement`) | Policy bawaan melanggar C3 (lihat 3.4) |
| **Penempatan VM ke DC** | Diserahkan ke broker | Direncanakan di awal dengan aturan yang sama | Bug CloudSim Plus 6.6.1: VM yang di-retry ke DC-2 tidak pernah menerima cloudlet (lihat 4) |
| **Real world** | Belum dirancang | 20 container Docker (PE, RAM, CPU, jaringan DC, penempatan host sesuai desain) | Tugas poin 5–6 |

---

## 3. Bagian Desain yang Kurang Tepat untuk Diterapkan

Temuan berikut muncul saat desain dijalankan di CloudSim. Semuanya bersifat desain, bukan bug kode. Untuk tiap temuan disertakan bukti angka dan rekomendasi revisi.

### 3.1 Rumus makespan f1 tidak sesuai dengan cloudlet 1 PE

**Desain:** `f1 = max Σ L_i / (MIPS_j × PE_j)` menganggap satu task bisa memakai **semua** PE VM. Di saat yang sama, desain menetapkan `numberOfPes = 1` per cloudlet.

**Bukti:** Di CloudSim, cloudlet 1 PE hanya berjalan di MIPS satu PE. Contoh 5 task memberi makespan 90 s menurut f1, tetapi **360,21 s** di CloudSim (task 900.000 MI ÷ 2.500 MIPS). Pada 9 skenario, CloudSim (tanpa kontensi RAM/BW) 4–83% lebih lambat dari f1. Selisih terbesar ada di S1, karena task besar menentukan makespan.

**Dampak:** KPB dan algoritma lain yang memakai f1 sebagai estimasi akan salah menilai VM besar (V3 dianggap 10.000 MIPS untuk satu task, padahal efektif 2.500).

**Rekomendasi:** Pilih salah satu.
- Ubah estimasi menjadi `ETC = L_i / MIPS_per_PE_j`, dan perhitungkan bahwa VM dengan p PE bisa menjalankan p task paralel.
- Atau set `numberOfPes` cloudlet = jumlah PE VM, supaya sesuai dengan f1.

### 3.2 RAM/BW 20% per cloudlet + `CloudletSchedulerTimeShared` membuat makespan membengkak

**Desain:** `UtilizationModelDynamic` 20% untuk RAM dan BW, dengan `CloudletSchedulerTimeShared`.

**Bukti:** Time-shared menjalankan semua cloudlet di satu VM secara bersamaan. Lima cloudlet sudah memakai 100% RAM VM; mulai cloudlet ke-6, CloudSim memakai virtual memory dan memperlambat eksekusi.

| Skenario | k | RAM/BW 20% (desain) | RAM/BW 0% | Faktor |
|:---:|:---:|---:|---:|:---:|
| S1 | 20% | 1.327 s | 514 s | 2,6× |
| S2 | 20% | 21.269 s | 1.706 s | 12,5× |
| S3 | 20% | 87.658 s | 3.422 s | **25,6×** |
| S3 | 100% | 15.060 s | 1.969 s | 7,6× |

**Dampak:** Hasil simulasi lebih dipengaruhi oleh model virtual memory CloudSim daripada oleh keputusan penjadwalan. Setiap algoritma yang mengonsolidasikan task di sedikit VM akan sangat dirugikan, dan angkanya tidak bisa dibandingkan dengan literatur yang memakai GoCJ.

**Rekomendasi:** Pilih salah satu.
- Pakai `CloudletSchedulerSpaceShared` (task antre per PE, sehingga hanya ≤ PE task yang aktif).
- Atau turunkan utilisasi RAM/BW menjadi nilai absolut kecil (mis. 50 MB per cloudlet).
- Atau pertahankan 20%, tetapi nyatakan secara eksplisit bahwa kontensi memori termasuk objek kajian.

### 3.3 Model energi membuat konsolidasi tidak pernah menghemat energi

**Desain bagian 3.3:** "mengonsolidasikan task ke sedikit host menghemat energi tetapi memperpanjang makespan."

**Desain bagian 3.2:** energi dihitung untuk **setiap** host dari t = 0 sampai Cmax, termasuk daya idle.

**Bukti:** Keenam host selalu menyala (total daya idle 741 W), sehingga energi hampir sebanding dengan makespan. Konsolidasi (k = 20%) justru boros:

| Skenario | k = 20% | k = 100% |
|:---:|---:|---:|
| S1 | 0,30 kWh | 0,11 kWh |
| S2 | 5,08 kWh | 1,05 kWh |
| S3 | 20,79 kWh | 3,82 kWh |

Hal yang sama terjadi di real world: konfigurasi tercepat selalu yang paling hemat energi.

**Dampak:** Konflik makespan–energi yang menjadi "objek kajian project" (bagian 3.3) tidak pernah muncul, karena kedua objektif selalu searah.

**Rekomendasi:** Host tanpa VM aktif harus bisa dimatikan. Misalnya dengan `Host.setIdleShutdownDeadline()` di CloudSim Plus, atau dengan menghitung energi host hanya sampai cloudlet terakhirnya selesai. Hanya dengan begitu konsolidasi bisa menghemat daya idle.

### 3.4 `VmAllocationPolicySimple` + `VmSchedulerTimeShared` melanggar batasan C3

**Desain:** C3 `Σ PE VM ≤ PE host`, dengan policy `VmAllocationPolicySimple` dan `VmSchedulerTimeShared`.

**Bukti:** Kombinasi bawaan ini hanya mengecek total MIPS. Seluruh 20 VM (72.000 MIPS) dimasukkan ke DC-1 (72.000 MIPS), satu host berisi 11 PE padahal hanya punya 8, dan DC-2 kosong.

**Dampak:** Desain "dua datacenter dengan karakter berbeda" tidak terwujud, karena DC-2 tidak pernah dipakai.

**Rekomendasi (sudah diterapkan):** Filter host yang menegakkan C3–C6 (`ConstrainedPlacement`), dengan aturan pemilihan tetap "host dengan PE terpakai paling sedikit".

### 3.5 Klaim "ruang bebas sekitar 37%" hanya berlaku dalam MIPS

**Desain bagian 2.4:** 72.000 MIPS permintaan VM terhadap 115.200 MIPS kapasitas host memberi ruang bebas ~37%.

**Bukti:** Dalam satuan PE (yang dibatasi C3), VM meminta 40 dari 48 PE (83%). DC-1 terisi **penuh** 24/24 PE, dan DC-2 16/24 PE. Ruang bebas sebenarnya hanya 17%, dan seluruhnya ada di DC-2.

**Rekomendasi:** Hitung rasio beban dalam PE, RAM, dan MIPS sekaligus, karena batasan yang paling ketat (PE) yang menentukan apakah alokasi gagal.

### 3.6 Protokol 10 run dengan seed acak tidak bermakna untuk konfigurasi ini

**Desain bagian 4.2:** 10 run dengan seed berbeda, lalu mean ± std dan Wilcoxon.

**Bukti:** Dataset tetap, waktu kedatangan tetap (t = 0), KPB deterministik, dan CloudSim deterministik. Dua run simulasi menghasilkan angka identik, sehingga std = 0 dan Wilcoxon tidak dapat dihitung.

**Rekomendasi:** Seed hanya relevan untuk algoritma stokastik (PSO) atau bila ada unsur acak dalam workload, misalnya urutan kedatangan diacak per seed. Variasi nyata baru muncul di real world.

### 3.7 Batasan lunak belum terdefinisi cukup jelas

**Desain bagian 4.4:** deadline `1,5 × (L_i / MIPS rata-rata)` dan utilisasi host ≤ 85%, "diberi penalti pada fungsi fitness."

**Masalah:**
- "MIPS rata-rata" ambigu: rata-rata kapasitas VM (3.600 MIPS), MIPS per PE, atau MIPS host.
- Penalti fitness hanya bermakna untuk metaheuristik. KPB tidak punya fungsi fitness.
- Dengan RAM/BW 20% (3.2), hampir semua task akan melanggar deadline apa pun definisinya.

**Rekomendasi:** Definisikan "MIPS rata-rata" secara eksplisit, lalu laporkan jumlah pelanggaran deadline sebagai metrik tambahan, bukan sebagai penalti.

### 3.8 Satuan biaya bandwidth dan formula biaya CloudSim

**Desain bagian 2.2:** "Cost per bandwidth $0,005 / $0,003" tanpa satuan.

**Temuan:** `VmCost` CloudSim membebankan BW **sekali** per kapasitas VM (Mbps), bukan per data yang ditransfer. Biaya CPU dihitung `costPerSecond × (MIPS VM ÷ MIPS per PE host) × lama VM hidup`, sehingga V3 di host A dikenai 3,3× tarif per detik. Biaya total jadi hampir sepenuhnya ditentukan oleh makespan.

**Rekomendasi:** Tuliskan satuan biaya (per Mbps, per GB transfer, atau per jam) dan formula yang dimaksud, atau sebutkan bahwa yang dipakai adalah formula `VmCost` CloudSim.

---

## 4. Status Risiko Teknis (Desain bagian 5.4)

| Risiko | Status | Penanganan |
|:---|:---:|:---|
| 1. Fitness memanggil simulasi penuh | ✅ Tidak relevan | KPB bukan metaheuristik; simulasi dijalankan sekali per konfigurasi |
| 2. Akumulasi energi di CloudSim Plus | ✅ Ditangani | `EnergyMeter` mengintegrasikan `P(u)` setiap clock tick (interval 1 s), termasuk host idle. Hasil integrasi cocok dengan perhitungan eksak (selisih < 0,1%) |
| 3. Kegagalan alokasi VM yang senyap | ✅ Ditangani | Validasi 20/20 VM, validasi setiap cloudlet berjalan di VM hasil KPB, dan semua cloudlet selesai |

Selain tiga risiko di desain, ditemukan **dua kegagalan senyap tambahan** di CloudSim Plus 6.6.1:

1. `bindCloudletToVm()` mengembalikan `false` tanpa error bila dipanggil sebelum `submitCloudletList()`. Broker lalu membagi task secara round-robin, sehingga hasil "KPB" sebenarnya hasil round-robin.
2. VM yang gagal di DC-1 lalu di-retry ke DC-2 berhasil dibuat, tetapi cloudlet-nya tidak pernah dikirim: 43 dari 100 cloudlet tertinggal berstatus QUEUED.

Keduanya sudah ditangani di `SimulationSetup` dan `ConstrainedPlacement`.

---

## 5. Gap Simulasi vs Real World

| Aspek | Simulasi (CloudSim) | Real world (Docker) | Dampak |
|:---|:---|:---|:---|
| Host fisik | 6 host terpisah | 1 laptop (Intel Core Ultra 7 155H, CPU hybrid); host hanya label | Interferensi antar VM: rasio waktu nyata/prediksi naik dari 1,4× (16 proses aktif) menjadi 2,6× (40 proses) di S3 |
| RAM/BW 20% per task | Ya | Tidak ditiru | Meniru 1,6 GB × ratusan task mustahil di laptop 8 GB. Pembanding yang adil adalah CloudSim RAM/BW 0% |
| Bandwidth dan file 300 KB | Dimodelkan | Tidak dibatasi / tidak ditransfer | Kecil, karena workload didominasi CPU |
| Energi | Integrasi utilisasi host | Model daya desain × CPU terukur (cgroup) | Estimasi, tanpa power meter / RAPL |
| Repetisi | 1× (deterministik) | 3× | Std makespan real world 2–19% dari mean |
| Tren makespan antar k | k = 100% selalu tercepat | Sama untuk S2; S3 berbalik (k = 20% tercepat) | Kontensi CPU hybrid dan thermal throttling pada beban panjang |

---

## 6. Rekomendasi Revisi Desain (Prioritas)

1. **Selaraskan model PE** (3.1): ETC per PE atau cloudlet multi-PE, supaya estimasi scheduler dan simulator konsisten.
2. **Perbaiki model RAM/BW** (3.2): `CloudletSchedulerSpaceShared` atau utilisasi absolut kecil. Ini perubahan dengan dampak terbesar pada angka hasil.
3. **Izinkan host idle dimatikan** (3.3), supaya trade-off makespan–energi benar-benar muncul.
4. **Tulis batasan C3 secara eksplisit sebagai filter alokasi** (3.4), dan hitung rasio beban per PE (3.5).
5. **Sesuaikan protokol eksperimen** (3.6): seed untuk algoritma stokastik saja; untuk real world, 3–5 run sudah cukup untuk demo.
6. **Lengkapi definisi** batasan lunak (3.7) dan satuan biaya (3.8).

---

## 7. Kesimpulan

- **Infrastruktur, workload, metrik, dan batasan keras** pada desain sudah diterapkan di CloudSim Plus dan direplikasi di real world (2 DC, 6 host, 20 VM, 8 metrik, C1–C8).
- **Perubahan yang disengaja** (KPB sebagai satu-satunya algoritma, tanpa weighted sum/Wilcoxon, repetisi, Java 11) mengikuti cakupan tugas minggu ini dan keterbatasan lingkungan.
- **Tiga keputusan desain berpengaruh besar dan sebaiknya direvisi**: rumus f1 yang mengabaikan batas 1 PE, RAM/BW 20% pada scheduler time-shared, dan model energi tanpa host shutdown. Ketiganya membuat hasil simulasi lebih ditentukan oleh model simulator daripada oleh algoritma penjadwalan, dan menghilangkan trade-off makespan–energi yang menjadi tujuan project.
