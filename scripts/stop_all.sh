#!/usr/bin/env bash
# Para todos los servicios arrancados con start_all.sh y espera a que terminen
cd "$(dirname "$0")/.."
RUN_DIR="${DATA_DIR:-$PWD/data}/run"
pids=""
for f in "$RUN_DIR"/*.pid; do
  [ -e "$f" ] || { echo "No hay servicios arrancados"; exit 0; }
  pid=$(cat "$f")
  kill "$pid" 2>/dev/null && echo "parado $(basename "$f" .pid) (pid $pid)" && pids="$pids $pid"
  rm -f "$f" "${f%.pid}.jvmpid"
done
# Espera (máx. 10 s) a que liberen puertos y ficheros: si no, un start_all.sh o un rm -rf data
# justo después pueden fallar (en Windows un fichero abierto no se puede borrar)
for i in $(seq 1 20); do
  vivos=""
  for pid in $pids; do kill -0 "$pid" 2>/dev/null && vivos="$vivos $pid"; done
  [ -z "$vivos" ] && break
  sleep 0.5
done
