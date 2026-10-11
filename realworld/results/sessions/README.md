# Sesi eksperimen real-world (KPB)

Setiap sesi berada di foldernya sendiri dan tidak pernah ditimpa. Mapping KPB diekspor dari Java (kode yang sama dengan CloudSim), sedangkan `scale` dan `itersPerMI` dikunci per sesi. Rekap grid dibuat dengan `python3 ../../analyze_grid.py <sesi>`.

| Sesi | Status | Catatan |
|:---|:---|:---|
| `gocj_20261009_230602` | **Belum selesai** | Grid `run_grid.sh` GoCJ 50–1.000 × k 20/50/100 × 3 repetisi (99 run, urutan acak seed 2026). Berhenti pada pukul 23:40 setelah 37 run selesai (summary.csv); log ke-38 tidak lengkap. Sebagian sel belum punya run sama sekali, jadi sesi ini belum dipakai di README. Jalankan ulang dengan sesi baru. |
| `verify_20261009_032144` | Verifikasi | Langkah 0 feedback KPB (rasio laju V1 : V2 : V3 = 1 : 2,83 : 10,49). |

Sesi eksperimen perbandingan KPB vs Harmony Search vs Clonal Selection (Fase 6b) sudah dihapus dari repo, karena repo ini kini hanya membahas KPB. Sesi tersebut masih bisa diambil dari riwayat git (commit `8efde06`).

Temuan dari sesi-sesi sebelumnya: `cpu_quota` membatasi waktu CPU, bukan kecepatan core. Pada CPU hybrid laptop (P-core/E-core, mode daya, AC/baterai), kerja CPU per detik bisa berubah hingga ±50% antar periode. Karena itu, sebelum menjalankan grid panjang, pastikan charger terpasang, mode daya Windows "Best performance", sleep "Never", dan laptop tidak dipakai. `run_grid.sh` mengecek mode daya lewat `scripts/power_state.sh`.
