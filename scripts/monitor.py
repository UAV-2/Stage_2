#!/usr/bin/env python3
"""Muestrea CPU y memoria de los servicios y del host mientras dura un experimento.

    python3 scripts/monitor.py --out benchmarks/results/resources.csv --tag "c=8" &
    ...experimento...
    kill %1

Lee los PIDs de data/run/*.pid (o *.jvmpid). Usa psutil si está instalado (anaconda lo trae)
y, si no, `ps` (CPU %% del proceso y RSS), que funciona en macOS y Linux. En Windows hace
falta psutil (pip install psutil).
"""
import argparse
import csv
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
try:
    import psutil
except ImportError:
    psutil = None

running = True


def stop(*_):
    global running
    running = False


def pids(run_dir):
    # *.jvmpid (PID real de la JVM, lo escribe start_all.sh) tiene prioridad sobre *.pid: en Git
    # Bash para Windows los *.pid son PIDs de MSYS que psutil no reconoce
    out = {}
    for pattern in ("*.pid", "*.jvmpid"):
        for f in sorted(Path(run_dir).glob(pattern)):
            try:
                out[f.stem] = int(f.read_text().strip())
            except ValueError:
                pass
    return out


def sample_ps(pid_map):
    if not pid_map:
        return {}
    res = subprocess.run(["ps", "-o", "pid=,%cpu=,rss=", "-p", ",".join(map(str, pid_map.values()))],
                         capture_output=True, text=True).stdout
    by_pid = {}
    for line in res.splitlines():
        p = line.split()
        if len(p) == 3:
            by_pid[int(p[0])] = (float(p[1]), int(p[2]) / 1024)
    return {name: by_pid[pid] for name, pid in pid_map.items() if pid in by_pid}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--tag", default="")
    ap.add_argument("--interval", type=float, default=0.5)
    ap.add_argument("--run-dir", default=str(ROOT / "data" / "run"))
    a = ap.parse_args()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    pid_map = pids(a.run_dir)
    procs = {n: psutil.Process(p) for n, p in pid_map.items()} if psutil else {}
    if psutil:
        for p in procs.values():
            p.cpu_percent(None)
        psutil.cpu_percent(None)
    new = not Path(a.out).exists()
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["timestamp", "tag", "process", "cpu_percent", "rss_mb"])
        t0 = time.time()
        while running:
            time.sleep(a.interval)
            now = round(time.time() - t0, 2)
            if psutil:
                for n, p in procs.items():
                    try:
                        w.writerow([now, a.tag, n, p.cpu_percent(None), round(p.memory_info().rss / 2**20, 1)])
                    except psutil.NoSuchProcess:
                        pass
                vm = psutil.virtual_memory()
                w.writerow([now, a.tag, "HOST", psutil.cpu_percent(None), round(vm.used / 2**20, 1)])
            else:
                for n, (cpu, rss) in sample_ps(pid_map).items():
                    w.writerow([now, a.tag, n, cpu, round(rss, 1)])
            f.flush()


if __name__ == "__main__":
    sys.exit(main())
