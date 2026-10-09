# Sesi eksperimen real-world (Fase 6b)

Grid: GoCJ 100 / 500 / 1000 × {KPB k=100%, HS seed 1–5, CSA seed 1–5} × 3 repetisi = 99 run, urutan acak (seed 2026), `itersPerMI=14` dan `scale` dikunci per sesi, `docker stats` direkam. Mapping diekspor sekali dari Java dan dipakai ulang oleh ketiga sesi (identik). Rekap: `recap.md`; validasi (CV per sel, rasio nyata/prediksi, drift waktu, CPU% per tier): `validation.md`.

| Sesi | Status | Catatan |
|:---|:---|:---|
| `experiment_20261009_204104` | **Final** | Satu kali duduk, power mode "Best performance", 99/99 run. Rasio stabil ±2,1 sampai run 74, lalu turun ke ±1,65 (mesin lebih cepat) mulai ±21:37; status baterai berubah dari AC (awal) ke discharging (akhir). |
| `experiment_20261009_033939` | Anomali, disimpan | Run 1–19 (03:40–03:54) rasio ±2,1; laptop sleep ±9 jam; run 20–99 (13:14–14:00) rasio ±1,52. Power mode "Better battery". |
| `experiment_rerun_20261009_195104` | Anomali, disimpan | Ulangan 19 run awal sesi di atas (mapping dan `scale` sama). Rasio ±2,27, sehingga tidak bisa digabung dengan 80 run stabil sesi di atas. |
| `verify_20261009_032144` | Verifikasi | Langkah 0 feedback KPB (rasio laju 1 : 2,83 : 10,49). |

Temuan: `cpu_quota` membatasi waktu CPU, bukan kecepatan core. Pada CPU hybrid laptop (P-core/E-core, power mode, daya AC/baterai), kerja per detik CPU berubah hingga ±50% antar periode, lebih besar dari selisih antar algoritma di CloudSim (0,7–15%). Contoh: di GoCJ 1000, HS memakai mapping yang sama persis dengan KPB, tetapi rata-rata real-world-nya berbeda 8% di sesi final.
