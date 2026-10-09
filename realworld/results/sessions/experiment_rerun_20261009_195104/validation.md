## Validasi sesi real-world

| Task | Sel | n | Makespan (s) | CV (%) | Nyata / prediksi | Status |
|---:|:---|---:|---:|---:|---:|:---|
| 100 | CSA seed 1 | 2 | 8.095 | 28.10 | 1.930 | **CV > 5%** |
| 100 | CSA seed 3 | 1 | 12.445 | 0.00 | 3.029 | ok |
| 100 | HS seed 2 | 1 | 9.193 | 0.00 | 2.073 | ok |
| 100 | HS seed 3 | 1 | 9.085 | 0.00 | 2.099 | ok |
| 100 | HS seed 4 | 2 | 10.831 | 8.96 | 2.487 | **CV > 5%** |
| 100 | KPB k=100% | 1 | 10.825 | 0.00 | 2.225 | ok |
| 500 | CSA seed 2 | 1 | 43.966 | 0.00 | 2.268 | ok |
| 500 | HS seed 1 | 1 | 37.360 | 0.00 | 1.916 | ok |
| 500 | HS seed 2 | 1 | 46.216 | 0.00 | 2.370 | ok |
| 1000 | CSA seed 2 | 1 | 90.926 | 0.00 | 2.353 | ok |
| 1000 | CSA seed 4 | 3 | 86.497 | 10.06 | 2.248 | **CV > 5%** |
| 1000 | CSA seed 5 | 1 | 85.712 | 0.00 | 2.226 | ok |
| 1000 | HS seed 3 | 1 | 96.865 | 0.00 | 2.463 | ok |
| 1000 | HS seed 5 | 1 | 84.663 | 0.00 | 2.153 | ok |
| 1000 | KPB k=100% | 1 | 94.189 | 0.00 | 2.395 | ok |

| Task | Rasio nyata/prediksi min | mean | max |
|---:|---:|---:|---:|
| 100 | 1.547 | 2.283 | 3.029 |
| 500 | 1.916 | 2.185 | 2.370 |
| 1000 | 1.988 | 2.292 | 2.463 |

Drift waktu (urutan eksekusi, blok 10 run):

| Run | Waktu selesai | Rasio mean | SD | Min | Max |
|:---|:---|---:|---:|---:|---:|
| 1–10 | 19:51:53–19:58:19 | 2.162 | 0.283 | 1.55 | 2.46 |
| 11–19 | 19:59:53–20:07:20 | 2.392 | 0.287 | 2.10 | 3.03 |

CPU% container saat sibuk (sampel ≥ 2%, docker stats):

| Tier | Limit (%) | Sampel | Median (%) | P90 (%) | Median / limit |
|:---|---:|---:|---:|---:|---:|
| V3 | 100 | 660 | 97.3 | 99.3 | 0.97 |
| V2 | 30 | 1262 | 27.1 | 28.3 | 0.90 |
| V1 | 10 | 1132 | 9.7 | 10.4 | 0.97 |

Sel dengan CV > 5%: 100 task, CSA seed 1 (CV 28.1%); 100 task, HS seed 4 (CV 9.0%); 1000 task, CSA seed 4 (CV 10.1%)
