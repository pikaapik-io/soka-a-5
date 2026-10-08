#!/usr/bin/env python3
"""Worker yang berjalan di setiap container ("VM").

Meniru CloudletSchedulerSpaceShared dari CloudSim:
  * Satu kontainer = satu VM = satu proses eksekusi. Task dikerjakan berurutan,
    satu per satu (antrean FIFO sesuai urutan pengiriman batch), bukan paralel.
  * Karena hanya satu task berjalan pada satu waktu, task itu memakai penuh
    kuota CPU kontainer (cpu_quota/cpu_period di docker-compose.yml), yaitu
    seluruh kapasitas VM (C_j) -- sama seperti model antrean scheduler
    ready[j] + L_i / C_j.

Endpoint:
  GET  /health -> {"vm": ..., "vm_type": ..., "sequential": true, "status": "ok"}
  POST /batch  {"tasks": [{"id": i, "iterations": n}, ...]}
               -> streaming NDJSON, satu baris per task begitu task selesai:
                  {"id": i, "finish": monotonic}
                  lalu baris terakhir {"vm": ..., "cpu_s": detik CPU cgroup}
"""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
import time

VM_NAME = os.environ.get("VM_NAME", "vm")
VM_TYPE = os.environ.get("VM_TYPE", "V1")
PORT = int(os.environ.get("PORT", "8000"))
CPU_STAT_V2 = "/sys/fs/cgroup/cpu.stat"                # cgroup v2: usage_usec
CPU_USAGE_V1 = "/sys/fs/cgroup/cpuacct/cpuacct.usage"  # cgroup v1: nanodetik


def burn(iterations):
    """Pekerjaan CPU deterministik (LCG), tanpa sleep."""
    x = 1
    for _ in range(iterations):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
    return x


def cgroup_cpu_seconds():
    """Total CPU yang dipakai container, dari cgroup."""
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
    """Kerjakan task satu per satu, sesuai urutan kedatangan di batch (FIFO)."""
    for task in tasks:
        burn(max(1, int(task["iterations"])))
        yield task["id"], time.monotonic()


class Handler(BaseHTTPRequestHandler):
    def _reply(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._reply({"vm": VM_NAME, "vm_type": VM_TYPE, "sequential": True, "status": "ok"})
        else:
            self._reply({"error": "not found"}, 404)

    def do_POST(self):
        if self.path != "/batch":
            self._reply({"error": "not found"}, 404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        # HTTP/1.0 tanpa Content-Length: setiap baris langsung terkirim ke runner,
        # sehingga runner bisa menampilkan cloudlet yang selesai secara real-time.
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        cpu_before = cgroup_cpu_seconds()
        for task_id, t in run_batch(request.get("tasks", [])):
            self._send_line({"id": task_id, "finish": t})
        self._send_line({"vm": VM_NAME, "cpu_s": cgroup_cpu_seconds() - cpu_before})

    def _send_line(self, payload):
        self.wfile.write((json.dumps(payload) + "\n").encode())
        self.wfile.flush()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
