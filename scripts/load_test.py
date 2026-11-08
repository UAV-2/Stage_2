#!/usr/bin/env python3
"""Prueba de carga de GET /search con N clientes concurrentes (benchmark de integración).

    python3 scripts/load_test.py --concurrency 1,2,4,8,16,32 --duration 20 \
        --out benchmarks/results/search_load.csv --tag "replicas=2,threads=32"

Cada cliente es un hilo con su propia conexión keep-alive que lanza consultas sin pausa
(bucle cerrado). Si hay varias réplicas de búsqueda (SEARCH_URLS, separadas por comas),
los clientes se reparten entre ellas por turnos: el cliente hace de balanceador.
Mide el throughput (consultas/s), la latencia (media, p50, p95 y p99) y los errores.
Limitación: el cliente en Python comparte la CPU con los servicios; con concurrencias muy
altas puede ser él el cuello de botella (se ve porque su CPU llega al 100 %).
"""
import argparse
import csv
import http.client
import os
import random
import statistics
import threading
import time
import urllib.parse
from pathlib import Path

DEFAULT_QUERIES = ["adventure", "island", "love", "war peace", "ship captain sea", "whale", "king queen",
                   "murder detective", "love&language=en", "sea&author=melville", "war&offset=20&limit=20"]


def worker(base, queries, stop_at, out, errors, seed):
    rnd = random.Random(seed)
    u = urllib.parse.urlparse(base)
    conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
    while time.perf_counter() < stop_at:
        q = rnd.choice(queries)
        term, _, extra = q.partition("&")
        path = "/search?q=" + urllib.parse.quote(term) + ("&" + extra if extra else "")
        t0 = time.perf_counter()
        try:
            conn.request("GET", path)
            r = conn.getresponse()
            r.read()
            if r.status != 200:
                errors.append(r.status)
                continue
            out.append((time.perf_counter() - t0) * 1000)
        except (OSError, http.client.HTTPException):
            errors.append("conn")
            conn.close()
            conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
    conn.close()


def pct(sorted_vals, p):
    return sorted_vals[min(len(sorted_vals) - 1, int(p / 100 * len(sorted_vals)))] if sorted_vals else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--urls", default=os.environ.get("SEARCH_URLS", "http://localhost:7003"))
    ap.add_argument("--concurrency", default="1,2,4,8,16,32")
    ap.add_argument("--duration", type=float, default=20)
    ap.add_argument("--warmup", type=float, default=3)
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--queries", help="fichero con una consulta por línea")
    ap.add_argument("--out", default="benchmarks/results/search_load.csv")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    urls = [x.strip() for x in a.urls.split(",") if x.strip()]
    queries = Path(a.queries).read_text().split("\n") if a.queries else DEFAULT_QUERIES
    queries = [q for q in queries if q.strip()]
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    new = not Path(a.out).exists()
    with open(a.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["tag", "replicas", "concurrency", "repeat", "duration_s", "requests", "errors",
                        "throughput_rps", "mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms"])
        for c in [int(x) for x in a.concurrency.split(",")]:
            for rep in range(1, a.repeats + 1):
                # calentamiento (no se mide)
                warm = []
                ts = [threading.Thread(target=worker, args=(urls[i % len(urls)], queries, time.perf_counter() + a.warmup, warm, [], i))
                      for i in range(c)]
                [t.start() for t in ts]
                [t.join() for t in ts]
                lat, errors = [], []
                t_start = time.perf_counter()
                ts = [threading.Thread(target=worker, args=(urls[i % len(urls)], queries, t_start + a.duration, lat, errors, 1000 * rep + i))
                      for i in range(c)]
                [t.start() for t in ts]
                [t.join() for t in ts]
                elapsed = time.perf_counter() - t_start
                s = sorted(lat)
                row = [a.tag, len(urls), c, rep, round(elapsed, 2), len(s), len(errors), round(len(s) / elapsed, 1),
                       round(statistics.fmean(s), 2) if s else None, round(pct(s, 50), 2) if s else None,
                       round(pct(s, 95), 2) if s else None, round(pct(s, 99), 2) if s else None,
                       round(s[-1], 2) if s else None]
                w.writerow(row)
                f.flush()
                print(f"c={c:>3} rep={rep}  {row[7]:>8} req/s  media {row[8]} ms  p95 {row[10]} ms  errores {len(errors)}", flush=True)


if __name__ == "__main__":
    main()
