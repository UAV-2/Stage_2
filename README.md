# Search Engine - Stage 2: arquitectura orientada a servicios

Big Data, Grado en Ciencia e Ingeniería de Datos (ULPGC). Grupo **`UAV-2`**.
Repositorio: `https://github.com/UAV-2/Stage_2`

El sistema está formado por tres microservicios Java 17 + Javalin + Gson y un orquestador:

| Proceso | Puerto | Qué hace |
|---|---|---|
| `ingestion-service` | 7001 | Descarga libros de Project Gutenberg y los guarda en el datalake (`YYYYMMDD/HH`) |
| `indexing-service` | 7002 | Extrae metadatos (SQLite), construye el índice invertido y lo publica por versiones |
| `search-service` | 7003 | API para el front-end: búsqueda con filtros, paginación y ranking |
| `control-module` | 7000 | Orquestador: ingesta → indexación → publicación, con reintentos y estado persistente |

Coordinación: **orquestación HTTP clásica**. La arquitectura, las réplicas, los hilos y la
concurrencia se explican en [`docs/architecture.md`](docs/architecture.md). La API está en
[`api/openapi.yaml`](api/openapi.yaml), y el inventario y los contratos en
[`api/ENDPOINTS.md`](api/ENDPOINTS.md).

```
common/              librería compartida: texto, tokenizer, datalake, metadatos, índice por segmentos, HTTP
ingestion-service/   indexing-service/   search-service/   control-module/
benchmarks/          microbenchmarks JMH (src/test/java) y gráficas
api/                 openapi.yaml, ENDPOINTS.md, examples.http
scripts/             arranque y parada, pipeline, comprobaciones de la API, carga, JMH
shared/              stopwords.txt, book_ids.txt
sample_data/books/   40 libros sintéticos en formato Gutenberg (pruebas sin red)
```

## 1. Requisitos y compilación

- Java 17 o superior, Maven 3.9+, Python 3 (scripts de prueba y gráficas) y `curl`.

```bash
mvn clean package                 # compila todo y pasa los tests (JUnit 5)
mvn clean package -DskipTests     # solo compilar
```

Cada servicio genera un jar ejecutable con sus dependencias, por ejemplo
`search-service/target/search-service.jar`. También se puede compilar un único servicio:

```bash
mvn -pl search-service -am package
```

## 2. Ejecutar todo en una máquina

```bash
scripts/start_all.sh            # 1 réplica de cada servicio; logs en logs/, datos en data/
scripts/run_pipeline.sh 20      # el Control Module procesa los 20 primeros libros de shared/book_ids.txt
curl "http://localhost:7003/search?q=adventure&language=en&limit=5"
scripts/stop_all.sh
```

Para probar **sin red**, con los libros sintéticos de muestra:

```bash
SOURCE=local:$PWD/sample_data/books SOURCE_DELAY_MS=0 scripts/start_all.sh
BOOK_IDS=$PWD/sample_data/books/book_ids.txt scripts/run_pipeline.sh 40
```

El Control Module también puede funcionar como servicio con API de administración:

```bash
INGESTION_URLS=http://localhost:7001 INDEXING_URLS=http://localhost:7002 \
  java -jar control-module/target/control-module.jar &
curl -X POST "http://localhost:7000/control/run?books=10"
curl http://localhost:7000/control/status
```

## 3. Configuración (variables de entorno)

| Variable | Servicio | Por defecto | Descripción |
|---|---|---|---|
| `PORT` | todos | 7000 / 7001 / 7002 / 7003 | Puerto HTTP |
| `HTTP_THREADS` | todos | 32 | Hilos máximos de Jetty por proceso |
| `REPLICA_ID` | todos | `<servicio>-<puerto>` | Nombre de la réplica (aparece en `/status`) |
| `DATA_DIR` | todos | `data` | Raíz de `datalake/`, `datamarts/` y `control/` (compartida por todos) |
| `STOPWORDS` | indexing, search | `shared/stopwords.txt` | Lista de stopwords |
| `CORS_ORIGINS` | todos | `*` | Orígenes permitidos para un front-end en el navegador |
| `SOURCE` | ingestion | `gutenberg` | `gutenberg`, `local:<carpeta>` o una URL con `{id}` |
| `SOURCE_DELAY_MS` | ingestion | 1000 | Espera mínima entre descargas (para no saturar Gutenberg) |
| `PUBLISH_MODE` | indexing | `batch` | `batch` (el Control llama a `/index/publish`) o `immediate` |
| `COMPACT_THRESHOLD` | indexing | 16 | Número de segmentos a partir del cual se compactan en segundo plano |
| `INDEX_THREADS` | indexing | nº de núcleos | Hilos para tokenizar en `/index/rebuild` |
| `RETIRED_GRACE_MS` | indexing | 60000 | Tiempo antes de borrar los segmentos sustituidos |
| `REFRESH_MS` | search | 500 | Cada cuánto se comprueba si hay una versión nueva del índice |
| `CACHE_ENTRIES` | search | 50000 | Entradas de la caché LRU de postings |
| `INGESTION_URLS` / `INDEXING_URLS` | control | `http://localhost:7001` / `:7002` | Réplicas, separadas por comas |
| `WORKERS` | control | 4 | Libros procesados en paralelo |
| `PUBLISH_BATCH` / `PUBLISH_INTERVAL_MS` | control | 25 / 2000 | Cuándo se publica: al llegar al lote o cuando pasa el intervalo |
| `MAX_RETRIES` / `RETRY_BACKOFF_MS` / `REQUEST_TIMEOUT_MS` | control | 3 / 500 / 120000 | Política de reintentos y timeout |
| `BOOK_IDS` | control | `shared/book_ids.txt` | Lista ordenada de libros a procesar |

**Réplicas e hilos en `start_all.sh`:**

```bash
INGESTION_REPLICAS=1 INDEXING_REPLICAS=2 SEARCH_REPLICAS=3 HTTP_THREADS=16 scripts/start_all.sh
# réplica r de cada servicio = puerto base + 10*r  (search: 7003, 7013, 7023)
# las URLs quedan en data/run/endpoints.env y run_pipeline.sh las usa automáticamente
```

## 4. Comprobar la API

- **Tests automáticos** (`mvn test`): cada servicio arranca en un puerto libre y se
  comprueba su contrato. Cubren búsqueda, filtros individuales y combinados, resultados
  vacíos, parámetros inválidos, paginación, idempotencia, errores 404/409/422 y el flujo del
  orquestador con reintentos y reanudación.
- **Contra los servicios en marcha:**

  ```bash
  scripts/start_all.sh && scripts/run_pipeline.sh 5
  python3 scripts/check_api.py        # 54 comprobaciones; termina con "54/54 comprobaciones correctas"
  ```

- **Ejemplos manuales:** [`api/examples.http`](api/examples.http), que se pueden ejecutar
  desde IntelliJ o VS Code.

## 5. Benchmarks

**Microbenchmarks JMH** (módulo `benchmarks`, en `src/test/java`): procesamiento de texto,
publicación del índice y búsqueda.

```bash
scripts/run_jmh.sh            # 5 warmup + 10 iteraciones, 2 forks -> benchmarks/results/jmh_all.csv
scripts/run_jmh.sh threads    # mismo benchmark con -t 1,2,4,8
scripts/run_jmh.sh quick      # comprobación rápida
```

**Benchmarks de integración** (opcionales): todo el sistema en esta máquina.

```bash
python3 scripts/fetch_books.py --from-id 1 --count 200   # una vez: libros reales en cache/
scripts/integration_bench.sh                             # pipeline + búsqueda bajo carga + CPU/RAM
python3 benchmarks/plot_results.py                       # gráficas en benchmarks/results/plots/
```

`integration_bench.sh` hace dos experimentos, cambiando un factor cada vez:

- **E1:** varía `WORKERS` y las réplicas de indexación, y mide libros por segundo y la
  latencia de cada llamada.
- **E2:** varía la concurrencia de clientes, `HTTP_THREADS` y las réplicas de búsqueda, y
  mide consultas por segundo y las latencias p50, p95 y p99.

En los dos se registra el CPU y el RSS de cada proceso y del host. Las características de la
máquina se guardan en `benchmarks/results/host.txt`.
