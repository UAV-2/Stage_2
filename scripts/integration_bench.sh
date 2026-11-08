#!/usr/bin/env bash
# Benchmarks de integración (opcionales en el enunciado): todo el sistema en esta máquina.
#
#   python3 scripts/fetch_books.py --from-id 1 --count 200     # una vez: libros reales en cache/
#   scripts/integration_bench.sh                               # usa cache/ si existe; si no, sample_data
#
# E1 pipeline: throughput de ingesta e indexación variando WORKERS del orquestador y las
#    réplicas de indexación (un factor cada vez).  -> benchmarks/results/pipeline.csv
# E2 búsqueda: latencia y throughput de GET /search variando la concurrencia de clientes,
#    los hilos HTTP y las réplicas de búsqueda.     -> benchmarks/results/search_load.csv
# CPU y memoria por proceso y del host durante cada experimento -> benchmarks/results/resources.csv
#
# Variables: BENCH_BOOKS (100), DURATION (15 s por punto), REPEATS (3), CONCURRENCY (1,2,4,8,16,32)
set -euo pipefail
cd "$(dirname "$0")/.."
RES=benchmarks/results
mkdir -p "$RES"
BENCH_BOOKS="${BENCH_BOOKS:-100}"
DURATION="${DURATION:-15}"
REPEATS="${REPEATS:-3}"
CONCURRENCY="${CONCURRENCY:-1,2,4,8,16,32}"
if [ -f cache/book_ids.txt ]; then
  export SOURCE="local:$PWD/cache" BOOK_IDS="$PWD/cache/book_ids.txt"
else
  echo "(no hay cache/: se usan los libros sintéticos de sample_data)"
  export SOURCE="local:$PWD/sample_data/books" BOOK_IDS="$PWD/sample_data/books/book_ids.txt"
fi
export SOURCE_DELAY_MS=0                 # leyendo de disco no hace falta esperar
export DATA_DIR="$PWD/data-bench"

host_info() {
  { echo "fecha: $(date)"; uname -a
    (sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 "model name" /proc/cpuinfo) | sed 's/^/CPU: /'
    echo "núcleos: $(getconf _NPROCESSORS_ONLN)"
    (sysctl -n hw.memsize 2>/dev/null | awk '{print "RAM: " $1/1073741824 " GB"}' || grep MemTotal /proc/meminfo)
    java -version 2>&1 | head -1; echo "SOURCE=$SOURCE BENCH_BOOKS=$BENCH_BOOKS"; } > "$RES/host.txt"
}

with_monitor() { # tag comando...
  local tag=$1; shift
  python3 scripts/monitor.py --out "$RES/resources.csv" --tag "$tag" --run-dir "$DATA_DIR/run" &
  local mon=$!
  "$@"
  kill $mon 2>/dev/null; wait $mon 2>/dev/null || true
}

pipeline_to_csv() { # tag fichero_json
  python3 - "$1" "$2" "$RES/pipeline.csv" <<'PY'
import csv, json, sys, os
tag, src, out = sys.argv[1:]
r = json.load(open(src))
row = {"tag": tag, "elapsed_s": r["elapsed_s"], "books_published": r["books_published"],
       "ingestion_books_s": r["ingestion_throughput_books_s"], "indexing_books_s": r["indexing_throughput_books_s"],
       "ingest_ms_mean": r["ingest_ms"].get("mean"), "ingest_ms_p95": r["ingest_ms"].get("p95"),
       "update_ms_mean": r["index_update_ms"].get("mean"), "update_ms_p95": r["index_update_ms"].get("p95"),
       "publish_call_ms_mean": r["publish_call_ms"].get("mean"), "publish_wait_ms_mean": r["publish_wait_ms"].get("mean"),
       "publish_wait_ms_p95": r["publish_wait_ms"].get("p95"), "retries": r["retries"], "failed": r["books_failed"]}
new = not os.path.exists(out)
with open(out, "a", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(row))
    if new: w.writeheader()
    w.writerow(row)
print(row)
PY
}

host_info
echo "== E1: pipeline (ingesta + indexación)"
for cfg in "WORKERS=1 INDEXING_REPLICAS=1" "WORKERS=4 INDEXING_REPLICAS=1" "WORKERS=8 INDEXING_REPLICAS=1" \
           "WORKERS=8 INDEXING_REPLICAS=2" "WORKERS=8 INDEXING_REPLICAS=4"; do
  for rep in $(seq 1 "$REPEATS"); do
    scripts/stop_all.sh >/dev/null 2>&1 || true
    rm -rf "$DATA_DIR"
    env $cfg scripts/start_all.sh >/dev/null
    tag="$cfg rep=$rep"
    with_monitor "E1 $tag" env $cfg scripts/run_pipeline.sh "$BENCH_BOOKS" > "$RES/.last_run.json" 2>/dev/null || true
    sed -n '/^{/,$p' "$RES/.last_run.json" > "$RES/.last_run_clean.json"
    pipeline_to_csv "$tag" "$RES/.last_run_clean.json"
  done
done
scripts/stop_all.sh >/dev/null 2>&1 || true

echo "== E2: búsqueda bajo carga (índice ya construido en $DATA_DIR)"
for cfg in "SEARCH_REPLICAS=1 HTTP_THREADS=8" "SEARCH_REPLICAS=1 HTTP_THREADS=32" \
           "SEARCH_REPLICAS=2 HTTP_THREADS=32" "SEARCH_REPLICAS=4 HTTP_THREADS=32"; do
  scripts/stop_all.sh >/dev/null 2>&1 || true
  env $cfg scripts/start_all.sh >/dev/null
  source "$DATA_DIR/run/endpoints.env"
  with_monitor "E2 $cfg" python3 scripts/load_test.py --urls "$SEARCH_URLS" --concurrency "$CONCURRENCY" \
      --duration "$DURATION" --repeats "$REPEATS" --out "$RES/search_load.csv" --tag "$cfg"
done
scripts/stop_all.sh >/dev/null 2>&1 || true
rm -f "$RES/.last_run.json" "$RES/.last_run_clean.json"
echo "Resultados en $RES/. Gráficas: python3 benchmarks/plot_results.py"
