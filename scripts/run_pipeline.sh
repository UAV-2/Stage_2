#!/usr/bin/env bash
# Ejecuta el Control Module una vez: procesa N libros nuevos de BOOK_IDS y termina.
#   scripts/run_pipeline.sh 20
# Usa las URLs de las réplicas arrancadas con start_all.sh. Variables: WORKERS (4),
# PUBLISH_BATCH (25), PUBLISH_INTERVAL_MS (2000), BOOK_IDS (shared/book_ids.txt)
set -euo pipefail
cd "$(dirname "$0")/.."
export DATA_DIR="${DATA_DIR:-$PWD/data}"
export BOOK_IDS="${BOOK_IDS:-$PWD/shared/book_ids.txt}"
source "$DATA_DIR/run/endpoints.env"
RUN_CONTROL="${RUN_CONTROL:-java ${JAVA_OPTS:--Xmx512m} -jar control-module/target/control-module.jar}"
$RUN_CONTROL --books "${1:-20}"
