#!/usr/bin/env python3
"""Worker yang berjalan di setiap container ("VM").

Meniru CloudletSchedulerSpaceShared dari CloudSim:
  * Satu kontainer = satu VM = satu proses eksekusi. Task dikerjakan berurutan,
    satu per satu (antrean FIFO sesuai urutan pengiriman batch), bukan paralel.
  * Karena hanya satu task berjalan pada satu waktu, task itu memakai penuh
    batas CPU container/Pod, yaitu seluruh kapasitas efektif VM (C_j) -- sama
    seperti model antrean scheduler ready[j] + L_i / C_j.

Endpoint:
  GET  /health -> {"vm": ..., "vm_type": ..., "sequential": true, "status": "ok"}
  POST /batch  {"tasks": [{"id": i, "iterations": n}, ...]}
               -> streaming NDJSON, satu baris per task begitu task selesai:
                  {"id": i, "finish": monotonic}
                  lalu baris terakhir {"vm": ..., "cpu_s": detik CPU cgroup}

Peran file ini dalam proyek
---------------------------
File yang sama dijalankan di 20 container (Docker Compose me-mount worker/ ke
/app; Kubernetes membangun image lewat realworld/Dockerfile). Yang membedakan
tiap container hanyalah environment variable (VM_NAME, VM_TYPE, PORT) dan batas
CPU:
  * V3 -> cpu_quota 10000 / cpu_period 10000 = 1,0 CPU (10.000 MIPS)
  * V2 -> 3000 / 10000 = 0,3 CPU (3.000 MIPS)
  * V1 -> 1000 / 10000 = 0,1 CPU (1.000 MIPS)
Jadi "MIPS" VM ditiru oleh pembatasan CPU kernel (cgroup), bukan oleh kode ini.
Kode ini hanya membakar CPU sebanyak jumlah iterasi yang diminta; makin kecil
batas CPU container, makin lama iterasi itu selesai.

Pemanggil: realworld/kpb_realworld.py (fungsi check_workers, calibrate, execute)
lewat port host 127.0.0.1:9100-9119 yang diteruskan ke port 8000 container/Pod.

Alur satu POST /batch:
  1. Terima daftar task (id + jumlah iterasi) dari runner.
  2. Kirim header HTTP 200 lebih dulu, lalu catat CPU cgroup awal.
  3. Kerjakan task satu per satu (FIFO); setiap selesai, kirim satu baris JSON
     berisi id task dan waktu selesai (time.monotonic()).
  4. Setelah semua selesai, kirim baris ringkasan berisi detik-CPU yang dipakai
     container selama batch (selisih CPU cgroup), dipakai runner untuk energi.
"""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
import time

# Identitas dan port worker, diisi dari "environment" di docker-compose.yml.
VM_NAME = os.environ.get("VM_NAME", "vm")
VM_TYPE = os.environ.get("VM_TYPE", "V1")
PORT = int(os.environ.get("PORT", "8000"))
# Lokasi statistik pemakaian CPU container. Docker modern memakai cgroup v2;
# path v1 disediakan sebagai cadangan untuk sistem lama.
CPU_STAT_V2 = "/sys/fs/cgroup/cpu.stat"                # cgroup v2: usage_usec
CPU_USAGE_V1 = "/sys/fs/cgroup/cpuacct/cpuacct.usage"  # cgroup v1: nanodetik


def burn(iterations):
    """Pekerjaan CPU deterministik (LCG), tanpa sleep.

    Inilah "eksekusi task" sesungguhnya: loop Linear Congruential Generator
    (x = (a*x + c) mod 2^31) sebanyak `iterations` kali. Runner mengirim
    iterations = panjang task (MI) x iters_per_mi, jadi lama loop ini sebanding
    dengan panjang task dibagi kuota CPU container (= L_i / C_j).

    Mengapa LCG dan bukan time.sleep()? sleep tidak memakai CPU, sehingga kuota
    CPU (MIPS) tidak berpengaruh. Loop ini murni memakai CPU, sehingga waktu
    selesainya benar-benar dibatasi oleh kuota CPU Docker.
    Operasi "& 0x7FFFFFFF" menjaga x tetap 31 bit, sehingga integer Python tidak
    membesar dan biaya setiap iterasi tetap konstan.

    Parameter:
        iterations: jumlah iterasi loop.
    Return:
        nilai akhir x (tidak dipakai, hanya hasil perhitungan).
    """
    x = 1
    for _ in range(iterations):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
    return x


def cgroup_cpu_seconds():
    """Total CPU yang dipakai container, dari cgroup.

    Return:
        float, total detik-CPU kumulatif yang sudah dipakai container sejak dibuat.
        Dicoba cgroup v2 dulu (baris "usage_usec" di cpu.stat, satuan mikrodetik),
        lalu cgroup v1 (cpuacct.usage, satuan nanodetik). Bila keduanya tidak bisa
        dibaca, kembalikan 0.0.
    Catatan: angka ini adalah CPU yang benar-benar terpakai. Contoh: container V1
    (0,1 CPU) yang sibuk 100 detik nyata akan memakai sekitar 10 detik-CPU.
    """
    try:
        with open(CPU_STAT_V2) as f:
            for line in f:
                key, value = line.split()
                if key == "usage_usec":
                    return int(value) / 1e6
    except OSError:
        pass
    try:
        with open(CPU_USAGE_V1) as f:
            return int(f.read()) / 1e9
    except OSError:
        return 0.0


def run_batch(tasks):
    """Kerjakan task satu per satu, sesuai urutan kedatangan di batch (FIFO).

    Generator: setelah satu task selesai, langsung yield (id task, waktu selesai)
    supaya handler bisa segera mengirimkannya ke runner sebelum task berikutnya
    dimulai. Waktu selesai memakai time.monotonic() (jam yang tidak mundur/lompat
    walau jam sistem diubah); runner mengurangkannya dengan t0 miliknya.
    max(1, ...) memastikan setiap task minimal menjalankan 1 iterasi.
    """
    for task in tasks:
        burn(max(1, int(task["iterations"])))
        yield task["id"], time.monotonic()


class Handler(BaseHTTPRequestHandler):
    """Penangan request HTTP worker (endpoint /health dan /batch).

    Server memakai HTTPServer biasa (bukan ThreadingHTTPServer), jadi hanya satu
    request yang dilayani pada satu waktu. Ini sejalan dengan model satu VM =
    satu antrean eksekusi.
    """

    def _reply(self, payload, status=200):
        """Kirim balasan JSON biasa (non-streaming) lengkap dengan Content-Length."""
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        """GET /health: laporkan identitas VM dan bahwa worker memakai model sekuensial.

        Dipakai check_workers() di runner untuk memastikan container hidup, tipe VM
        benar, dan versi worker sudah versi antrean sekuensial (sequential: true).
        Path lain dibalas 404.
        """
        if self.path == "/health":
            self._reply({"vm": VM_NAME, "vm_type": VM_TYPE, "sequential": True, "status": "ok"})
        else:
            self._reply({"error": "not found"}, 404)

    def do_POST(self):
        """POST /batch: eksekusi semua task di batch secara berurutan dan stream hasilnya.

        Body request: {"tasks": [{"id": i, "iterations": n}, ...]}.
        Balasan: NDJSON, satu baris {"id", "finish"} per task selesai, lalu satu
        baris akhir {"vm", "cpu_s"}. Path selain /batch dibalas 404.
        """
        if self.path != "/batch":
            self._reply({"error": "not found"}, 404)
            return
        # Baca body JSON sesuai panjang yang dinyatakan header Content-Length.
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        # HTTP/1.0 tanpa Content-Length: setiap baris langsung terkirim ke runner,
        # sehingga runner bisa menampilkan cloudlet yang selesai secara real-time.
        # (Akhir balasan ditandai dengan koneksi ditutup setelah request selesai.)
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        # CPU kumulatif sebelum batch; selisih dengan nilai sesudah batch = CPU batch ini.
        cpu_before = cgroup_cpu_seconds()
        for task_id, t in run_batch(request.get("tasks", [])):
            self._send_line({"id": task_id, "finish": t})
        self._send_line({"vm": VM_NAME, "cpu_s": cgroup_cpu_seconds() - cpu_before})

    def _send_line(self, payload):
        """Tulis satu baris NDJSON lalu flush agar langsung terkirim, tidak tertahan buffer."""
        self.wfile.write((json.dumps(payload) + "\n").encode())
        self.wfile.flush()

    def log_message(self, *args):
        """Matikan log akses bawaan BaseHTTPRequestHandler agar output container bersih."""
        pass


if __name__ == "__main__":
    # Dengarkan di semua interface container; Docker meneruskan 127.0.0.1:91xx host ke sini.
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
