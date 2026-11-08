# Inventario de endpoints y contratos de coordinación

La especificación completa (parámetros, esquemas, códigos de estado y ejemplos) está en
[`openapi.yaml`](openapi.yaml), que se puede abrir en https://editor.swagger.io.

Todos los procesos se ejecutan en la misma máquina. Puertos por defecto: control 7000,
ingestion 7001, indexing 7002, search 7003. Cada réplica extra usa el puerto base + 10.
Todos los errores siguen el formato `{"error", "message", "status"}`.

## 1. API para el front-end

**Proveedor:** Search Service. **Consumidor:** front-end. Todas las operaciones son de solo
lectura y síncronas.

| Método | Ruta | Para qué | Códigos |
|---|---|---|---|
| GET | `/search?q&author&language&year&offset&limit` | Búsqueda AND con filtros combinables y paginación | 200, 400 |
| GET | `/books/{book_id}` | Detalle de un libro ya buscable | 200, 400, 404 |
| GET | `/search/filters` | Valores para los filtros (idiomas, rango de años, autores) | 200 |

### Semántica de `/search`

- **Parámetros**:
  - `q` es obligatorio (máximo 500 caracteres).
  - `limit` vale 20 por defecto, con un mínimo de 1 y un máximo de 100.
  - `offset` vale 0 por defecto y tiene que ser ≥ 0.
- **Consulta**: se tokeniza igual que los libros y los términos se combinan con AND. Si `q`
  solo tiene stopwords, la respuesta es 200 con `total: 0`.
- **Filtros**:
  - `author`: subcadena, sin distinguir mayúsculas.
  - `language`: código exacto (`en`, `fr`, ...), sin distinguir mayúsculas.
  - `year`: año exacto. Es el de la publicación original si el header lo indica; si no, el
    de su publicación en Gutenberg.
- **Orden**: por score tf-idf de mayor a menor; los empates, por `book_id` ascendente.
- **Paginación**: `total` cuenta todas las coincidencias y `results` solo la página pedida.
  Un `offset` mayor que `total` devuelve 200 con `results: []`.
- **Errores (400)**: `MISSING_QUERY`, `QUERY_TOO_LONG`, `INVALID_AUTHOR`, `INVALID_LANGUAGE`,
  `INVALID_YEAR`, `INVALID_OFFSET`, `INVALID_LIMIT`.
- **Consistencia**: cada respuesta indica con qué versión publicada del índice se calculó
  (`index_version`).
- **CORS**: se controla con `CORS_ORIGINS` (por defecto `*`), así que un front-end en otro
  origen puede llamar directamente al Search Service.

## 2. APIs internas

**Consumidor:** Control Module. Todas son síncronas: un 200 significa que la operación ha
terminado.

| Proveedor | Método | Ruta | Para qué | Paso del flujo | Idempotencia y reintentos |
|---|---|---|---|---|---|
| Ingestion | POST | `/ingest/{book_id}` | Descargar el libro, separar header/body y guardarlo | 1 | Idempotente (`already_present`). Reintentar en caso de error de red o 502; no reintentar con 404/422 |
| Ingestion | GET | `/ingest/status/{book_id}` | Saber si el libro está disponible | Diagnóstico | Solo lectura |
| Ingestion | GET | `/ingest/list` | Listar los libros del datalake | Diagnóstico | Solo lectura |
| Indexing | POST | `/index/update/{book_id}` | Guardar los metadatos y dejar los términos pendientes | 2 | Idempotente (`already_indexed`). 409 si el libro aún no está ingerido |
| Indexing | POST | `/index/publish` | Hacer buscables los libros pendientes (nueva versión) | 3 | Idempotente (`skipped`); sin pendientes no cambia nada |

**Timeouts y fallos para el llamador** (`REQUEST_TIMEOUT_MS`, por defecto 120 s):

- Un error de red, un timeout o un 5xx se reintenta hasta `MAX_RETRIES` veces con espera
  exponencial (`RETRY_BACKOFF_MS`). Para la ingesta se prueba con la siguiente réplica.
- Si una réplica de indexación no consigue publicar varias veces seguidas, sus libros
  pendientes se reenvían a otra réplica.
- Lo que no se completa queda reflejado en los ficheros de control y se retoma en la
  siguiente ejecución.

## 3. Endpoints operacionales y de administración

| Proveedor | Método | Ruta | Consumidor | Para qué |
|---|---|---|---|---|
| Todos | GET | `/status` | Control Module (espera de arranque), scripts de benchmark, operadores | Salud, pid, hilos, heap, CPU del proceso y estadísticas del servicio |
| Indexing | GET | `/index/status` | Operadores, benchmarks | Libros indexados, versión, segmentos, pendientes y tamaño |
| Indexing | POST | `/index/rebuild` | Administración | Reconstruir índice y metadatos desde el datalake (síncrono) |
| Indexing | POST | `/index/compact` | Administración | Fusionar segmentos (también es automático por encima de `COMPACT_THRESHOLD`) |
| Control | POST | `/control/run?books=N` | Administración | Lanzar una ejecución (asíncrona, 202; 409 si ya hay una en curso) |
| Control | GET | `/control/status` | Administración | Fase y métricas de la última ejecución |

## 4. Contratos que no son HTTP (almacenamiento compartido en un solo host)

### Datalake: el libro está listo para indexar

- **Propietario:** Ingestion Service.
- **Lectores:** Indexing Service.
- **Fichero:** `data/datalake/_locations.tsv`, con líneas `book_id \t YYYYMMDD/HH`.
- **Regla:** primero se escriben el body y el header (temporal + rename atómico) y después
  se añade la línea al fichero. Un libro está **disponible si y solo si** su línea existe.
- **Concurrencia:**
  - Lock por libro (`.locks/book-<id%64>.lock`).
  - Lock del fichero de ubicaciones (`.locks/locations.lock`).
  - Los dos combinan `FileLock` (entre procesos) y `ReentrantLock` (entre hilos).

### Índice publicado: versión visible para la búsqueda

- **Propietarios:** Indexing Service (todas sus réplicas).
- **Lectores:** Search Service.
- **Ficheros:**
  - `data/datamarts/index/manifest.json`: `version`, `segments`, `books` y `retired`.
  - `data/datamarts/index/segments/<seg>/`: segmentos inmutables con la estructura de
    carpetas del Stage 1, con líneas `book_id \t tf`.
- **Publicar:**
  1. Se escribe el segmento en `<seg>.tmp` y se renombra (fuera del lock).
  2. Con el lock `.manifest.lock` se lee el manifest, se le añade el segmento y se
     reescribe de forma atómica.
- **Leer:** la búsqueda relee el manifest cada `REFRESH_MS` (500 ms) y cada consulta usa una
  única versión. Los segmentos sustituidos al compactar o reconstruir se borran pasado
  `RETIRED_GRACE_MS` (60 s).
- **Duplicados:** un libro que ya está en `books` no se vuelve a publicar.

### Metadatos

- **Propietario:** Indexing Service.
- **Lectores:** Search Service.
- **Base de datos:** SQLite `data/datamarts/metadata.db` en modo WAL (un escritor y muchos
  lectores, también entre procesos), con `busy_timeout` de 10 s y upsert por `book_id`.
- **Orden:** los metadatos se guardan **antes** de publicar los términos. Como la búsqueda
  parte siempre del índice publicado, nunca muestra un libro sin metadatos.

### Estado del flujo

- **Propietario:** Control Module (un único proceso).
- **Ficheros:** en `data/control/`: `downloaded_books.txt`, `indexed_books.txt` (libros ya
  publicados) y `failed_books.txt`.
- **Regla:** se añade una línea solo **después** de que el servicio confirme la operación.

**Si el Control Module se para:**

| Momento del corte | Qué pasa al reanudar |
|---|---|
| Después de la ingesta y antes de registrarla | Se repite la ingesta (`already_present: true`) |
| Después del update y antes de publicar | Si la réplica sigue viva, el siguiente `/index/publish` lo publica. Si se reinició, el libro aparece en `downloaded − indexed` y se vuelve a indexar |
| Después de publicar y antes de registrarlo | El update devuelve `already_indexed: true` y se registra |

## 5. Suposiciones de una sola máquina (a cambiar en el Stage 3)

- Las URLs `localhost:<puerto>` y la lista estática de réplicas, que se pasa por variables
  de entorno.
- El datalake y los datamarts compartidos como ficheros locales: hacen falta un
  almacenamiento distribuido o compartido, o que cada servicio exponga sus datos por API.
- Los `FileLock` y el rename atómico solo son fiables en un sistema de ficheros local. Hay
  que sustituirlos por un coordinador (bloqueos distribuidos o transacciones de la base de
  datos).
- SQLite es un fichero local con un único escritor. Hay que pasar a una base de datos
  cliente-servidor (por ejemplo, PostgreSQL).
- El Control Module es un único proceso. Hace falta elegir un líder o usar una cola
  persistente (un broker) para repartir el trabajo y sobrevivir a la caída de un nodo.
