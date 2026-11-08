#!/usr/bin/env bash
# Arranca todos los servicios en ESTA máquina (Stage 2: un solo host).
#
#   scripts/start_all.sh                       1 réplica de cada servicio
#   INDEXING_REPLICAS=2 SEARCH_REPLICAS=3 scripts/start_all.sh
#
# Puertos: réplica r (0, 1, 2...) de cada servicio = puerto base + 10*r
#   ingestion 7001, 7011, ...   indexing 7002, 7012, ...   search 7003, 7013, ...
# Variables: DATA_DIR (data), SOURCE (gutenberg | local:<dir>), HTTP_THREADS (32),
#   INGESTION_REPLICAS / INDEXING_REPLICAS / SEARCH_REPLICAS (1), JAVA_OPTS (-Xmx1g)
# Logs en logs/, PIDs en data/run/. Para parar: scripts/stop_all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export DATA_DIR="${DATA_DIR:-$PWD/data}"
export STOPWORDS="${STOPWORDS:-$PWD/shared/stopwords.txt}"
export SOURCE="${SOURCE:-gutenberg}"
# Git Bash en Windows: Java no entiende rutas /c/Users/... Git Bash traduce solo DATA_DIR y
# STOPWORDS, pero no SOURCE=local:<dir> (empieza por "local:"), así que se convierte aquí.
if [[ "$SOURCE" == local:* ]] && command -v cygpath >/dev/null 2>&1; then
  export SOURCE="local:$(cygpath -m "${SOURCE#local:}")"
fi
JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
ING_N="${INGESTION_REPLICAS:-1}"
IDX_N="${INDEXING_REPLICAS:-1}"
SRC_N="${SEARCH_REPLICAS:-1}"
RUN_DIR="$DATA_DIR/run"
mkdir -p "$RUN_DIR" logs

# Comando de cada servicio (se puede sobreescribir, p. ej. para usar el classpath de un IDE)
RUN_INGESTION="${RUN_INGESTION:-java $JAVA_OPTS -jar ingestion-service/target/ingestion-service.jar}"
RUN_INDEXING="${RUN_INDEXING:-java $JAVA_OPTS -jar indexing-service/target/indexing-service.jar}"
RUN_SEARCH="${RUN_SEARCH:-java $JAVA_OPTS -jar search-service/target/search-service.jar}"

for jar in ingestion-service indexing-service search-service; do
  if [[ -z "${RUN_OVERRIDE:-}" && ! -f "$jar/target/$jar.jar" ]]; then
    echo "Falta $jar/target/$jar.jar: compila antes con 'mvn -q package -DskipTests'"; exit 1
  fi
done

port_in_use() { # lsof en macOS/Linux; sin lsof (Git Bash en Windows) se intenta conectar
  if command -v lsof >/dev/null 2>&1; then lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; fi
}

start() { # nombre puerto comando
  local name=$1 port=$2; shift 2
  if port_in_use "$port"; then
    echo "El puerto $port ya está en uso (¿servicios ya arrancados? usa scripts/stop_all.sh)"; exit 1
  fi
  PORT=$port REPLICA_ID=$name nohup $@ > "logs/$name.log" 2>&1 &
  echo $! > "$RUN_DIR/$name.pid"
  echo "  $name -> http://localhost:$port  (pid $!, log logs/$name.log)"
}

urls() { # base n
  local base=$1 n=$2 out="" r
  for ((r = 0; r < n; r++)); do out="$out${out:+,}http://localhost:$((base + 10 * r))"; done
  echo "$out"
}

echo "Arrancando servicios (DATA_DIR=$DATA_DIR, SOURCE=$SOURCE)"
for ((r = 0; r < ING_N; r++)); do start "ingestion-$r" $((7001 + 10 * r)) $RUN_INGESTION; done
for ((r = 0; r < IDX_N; r++)); do start "indexing-$r"  $((7002 + 10 * r)) $RUN_INDEXING; done
for ((r = 0; r < SRC_N; r++)); do start "search-$r"    $((7003 + 10 * r)) $RUN_SEARCH; done

# URLs para el Control Module y los scripts de prueba
cat > "$RUN_DIR/endpoints.env" <<ENV
export INGESTION_URLS=$(urls 7001 "$ING_N")
export INDEXING_URLS=$(urls 7002 "$IDX_N")
export SEARCH_URLS=$(urls 7003 "$SRC_N")
ENV

echo "Esperando a que respondan..."
for url in $(tr ',' ' ' <<< "$(urls 7001 "$ING_N"),$(urls 7002 "$IDX_N"),$(urls 7003 "$SRC_N")"); do
  for i in $(seq 1 60); do
    st=$(curl -sf "$url/status") && break
    sleep 0.5
    [ "$i" = 60 ] && { echo "  $url no responde; revisa logs/"; exit 1; }
  done
  # PID real de la JVM según el propio servicio, para scripts/monitor.py (en Git Bash para
  # Windows, $! es un PID de MSYS que psutil no reconoce; en macOS/Linux coinciden)
  name=$(sed -nE 's/.*"replica_id":"([^"]+)".*/\1/p' <<< "$st")
  if [ -n "$name" ]; then sed -nE 's/.*"pid":([0-9]+).*/\1/p' <<< "$st" > "$RUN_DIR/$name.jvmpid"; fi
done
echo "Listo. Endpoints en $RUN_DIR/endpoints.env"
