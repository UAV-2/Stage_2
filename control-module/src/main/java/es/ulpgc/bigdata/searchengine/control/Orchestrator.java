package es.ulpgc.bigdata.searchengine.control;

import com.google.gson.JsonArray;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orquestación clásica (Control Module): decide qué libro se procesa, llama a las APIs
 * internas, guarda el progreso y reintenta.
 *
 * Por cada libro (WORKERS libros en paralelo):
 *   1. POST /ingest/{id} en una réplica de ingesta (round-robin; si falla, la siguiente).
 *      200 -> downloaded_books.txt.  404/422 -> failed_books.txt (no se reintenta).
 *   2. POST /index/update/{id} en la réplica de indexación id % N (afinidad: el mismo libro
 *      va siempre a la misma réplica, así no se indexa dos veces en paralelo).
 *   3. Cada PUBLISH_INTERVAL_MS, o al acumular PUBLISH_BATCH libros en una réplica,
 *      POST /index/publish. Los ids confirmados pasan a indexed_books.txt (ya buscables).
 *
 * Fallos: errores de red y 5xx se reintentan con espera exponencial (MAX_RETRIES). Si una
 * réplica de indexación no consigue publicar, sus libros se reenvían a otra réplica.
 */
public final class Orchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(Orchestrator.class);

    public record Settings(List<String> ingestionUrls, List<String> indexingUrls, int workers, int publishBatch,
                           long publishIntervalMs, int maxRetries, long backoffMs) {}

    private final Settings s;
    private final ControlState state;
    private final ServiceClient http;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final Map<String, Set<Integer>> awaitingPublish = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> publishLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> publishFailures = new ConcurrentHashMap<>();
    private final Map<Integer, Long> updatedAtNanos = new ConcurrentHashMap<>();
    private volatile RunMetrics metrics = null;
    private volatile String phase = "idle";

    public Orchestrator(Settings settings, ControlState state, ServiceClient http) {
        this.s = settings;
        this.state = state;
        this.http = http;
        for (String u : s.indexingUrls()) {
            awaitingPublish.put(u, ConcurrentHashMap.newKeySet());
            publishLocks.put(u, new ReentrantLock());
            publishFailures.put(u, new AtomicInteger());
        }
    }

    public String phase() { return phase; }

    public RunMetrics metrics() { return metrics; }

    /** Procesa hasta {@code limit} libros nuevos de la lista (en orden) más los pendientes de antes. */
    public synchronized RunMetrics run(List<Integer> bookIds, int limit) throws InterruptedException {
        RunMetrics m = new RunMetrics();
        metrics = m;
        phase = "waiting_for_services";
        waitForServices();

        List<Integer> recovered = new ArrayList<>(state.downloadedNotIndexed());
        recovered.sort(null);
        List<Integer> fresh = new ArrayList<>();
        for (int id : bookIds) {
            if (fresh.size() >= limit) break;
            if (!state.isDownloaded(id) && !state.isFailed(id)) fresh.add(id);
        }
        m.requested = fresh.size();
        m.recovered = recovered.size();
        LOG.info("Ejecución {}: {} libros nuevos, {} pendientes de indexar de una ejecución anterior",
                m.runId, fresh.size(), recovered.size());

        phase = "running";
        ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor();
        publisher.scheduleWithFixedDelay(this::publishAll, s.publishIntervalMs(), s.publishIntervalMs(), TimeUnit.MILLISECONDS);
        ExecutorService pool = Executors.newFixedThreadPool(s.workers());
        for (int id : recovered) pool.submit(() -> safely(id, () -> index(id, -1)));
        for (int id : fresh) pool.submit(() -> safely(id, () -> { if (ingest(id)) index(id, -1); }));
        pool.shutdown();
        pool.awaitTermination(7, TimeUnit.DAYS);
        publisher.shutdown();
        publisher.awaitTermination(1, TimeUnit.MINUTES);

        phase = "final_publish";
        for (int round = 0; round <= s.maxRetries() && pendingPublish() > 0; round++) {
            publishAll();
            if (pendingPublish() > 0) Thread.sleep(s.backoffMs() << Math.min(round, 6));
        }
        if (pendingPublish() > 0) LOG.warn("{} libros quedan sin publicar; se reintentarán en la próxima ejecución", pendingPublish());
        m.endNanos = System.nanoTime();
        phase = "idle";
        LOG.info("Ejecución {} terminada: {}", m.runId, m.toMap());
        return m;
    }

    private interface Step { void run() throws Exception; }

    private void safely(int id, Step step) {
        try {
            step.run();
        } catch (Exception e) {
            LOG.error("Libro {}: error inesperado", id, e);
        }
    }

    // ------------------------------------------------------------------ ingesta
    private boolean ingest(int id) throws IOException, InterruptedException {
        int n = s.ingestionUrls().size();
        int start = Math.floorMod(roundRobin.getAndIncrement(), n);
        for (int attempt = 0; attempt <= s.maxRetries(); attempt++) {
            String url = s.ingestionUrls().get((start + attempt) % n) + "/ingest/" + id;
            long t0 = System.nanoTime();
            try {
                ServiceClient.Response r = http.post(url);
                if (r.ok() && "downloaded".equals(r.str("status"))) {
                    metrics.ingestMs.add((System.nanoTime() - t0) / 1_000_000);
                    if (r.body().has("already_present") && r.body().get("already_present").getAsBoolean())
                        metrics.alreadyIngested.incrementAndGet();
                    else metrics.ingested.incrementAndGet();
                    state.markDownloaded(id);
                    return true;
                }
                if (r.status() == 404 || r.status() == 422 || r.status() == 400) {
                    state.markFailed(id, r.status() + " " + r.str("error"));
                    metrics.failed.incrementAndGet();
                    return false;
                }
                LOG.warn("Ingesta de {} en {}: HTTP {} {}", id, url, r.status(), r.str("error"));
            } catch (IOException e) {
                LOG.warn("Ingesta de {} en {}: {}", id, url, e.toString());
            }
            backoff(attempt);
        }
        metrics.gaveUp.incrementAndGet();
        return false;
    }

    // ------------------------------------------------------------------ indexación
    /** @param avoid índice de réplica a evitar (-1 = ninguna) */
    private void index(int id, int avoid) throws InterruptedException {
        int n = s.indexingUrls().size();
        int preferred = Math.floorMod(id, n);
        if (preferred == avoid && n > 1) preferred = (preferred + 1) % n;
        for (int attempt = 0; attempt <= s.maxRetries(); attempt++) {
            int k = (preferred + attempt) % n;
            if (k == avoid && n > 1) k = (k + 1) % n;
            String base = s.indexingUrls().get(k);
            long t0 = System.nanoTime();
            try {
                ServiceClient.Response r = http.post(base + "/index/update/" + id);
                if (r.ok()) {
                    metrics.updateMs.add((System.nanoTime() - t0) / 1_000_000);
                    boolean already = r.body().has("already_indexed") && r.body().get("already_indexed").getAsBoolean();
                    boolean published = r.body().has("published") && r.body().get("published").getAsBoolean();
                    if (already || published) {
                        markPublished(id);
                    } else {
                        updatedAtNanos.put(id, System.nanoTime());
                        Set<Integer> waiting = awaitingPublish.get(base);
                        waiting.add(id);
                        if (waiting.size() >= s.publishBatch()) publish(base);
                    }
                    return;
                }
                LOG.warn("Indexación de {} en {}: HTTP {} {}", id, base, r.status(), r.str("error"));
            } catch (IOException e) {
                LOG.warn("Indexación de {} en {}: {}", id, base, e.toString());
            }
            backoff(attempt);
        }
        metrics.gaveUp.incrementAndGet();
    }

    private void publishAll() {
        for (String base : s.indexingUrls()) {
            if (!awaitingPublish.get(base).isEmpty()) publish(base);
        }
    }

    /** Pide a una réplica que publique sus pendientes y registra los libros confirmados. */
    private void publish(String base) {
        ReentrantLock lock = publishLocks.get(base);
        if (!lock.tryLock()) return; // ya hay una publicación en curso para esa réplica
        try {
            Set<Integer> waiting = awaitingPublish.get(base);
            Set<Integer> snapshot = new HashSet<>(waiting);
            if (snapshot.isEmpty()) return;
            long t0 = System.nanoTime();
            ServiceClient.Response r;
            try {
                r = http.post(base + "/index/publish");
            } catch (IOException e) {
                r = null;
                LOG.warn("Publicación en {}: {}", base, e.toString());
            }
            metrics.publishCalls.incrementAndGet();
            if (r == null || !r.ok()) {
                if (publishFailures.get(base).incrementAndGet() > s.maxRetries()) redispatch(base, snapshot);
                return;
            }
            publishFailures.get(base).set(0);
            metrics.publishMs.add((System.nanoTime() - t0) / 1_000_000);
            Set<Integer> confirmed = new HashSet<>();
            for (String field : new String[]{"published", "skipped"}) {
                JsonArray a = r.body().getAsJsonArray(field);
                if (a != null) a.forEach(e -> confirmed.add(e.getAsInt()));
            }
            for (int id : snapshot) {
                if (confirmed.contains(id)) {
                    waiting.remove(id);
                    markPublished(id);
                }
            }
            snapshot.removeAll(confirmed);
            if (!snapshot.isEmpty()) {
                // la réplica no conocía estos libros (p. ej. se reinició y perdió sus pendientes)
                LOG.warn("{} libros no estaban pendientes en {}; se reenvían", snapshot.size(), base);
                redispatch(base, snapshot);
            }
        } finally {
            lock.unlock();
        }
    }

    private void redispatch(String failedBase, Set<Integer> ids) {
        int avoid = s.indexingUrls().indexOf(failedBase);
        awaitingPublish.get(failedBase).removeAll(ids);
        publishFailures.get(failedBase).set(0);
        Thread t = new Thread(() -> {
            for (int id : ids) {
                metrics.redispatched.incrementAndGet();
                try {
                    index(id, s.indexingUrls().size() > 1 ? avoid : -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "redispatch");
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markPublished(int id) {
        try {
            if (!state.isIndexed(id)) {
                state.markIndexed(id);
                metrics.published.incrementAndGet();
                Long t = updatedAtNanos.remove(id);
                if (t != null) metrics.publishWaitMs.add((System.nanoTime() - t) / 1_000_000);
            }
        } catch (IOException e) {
            LOG.error("No se pudo registrar el libro {} como indexado", id, e);
        }
    }

    private int pendingPublish() {
        return awaitingPublish.values().stream().mapToInt(Set::size).sum();
    }

    private void backoff(int attempt) throws InterruptedException {
        metrics.retries.incrementAndGet();
        Thread.sleep(s.backoffMs() << Math.min(attempt, 6));
    }

    private void waitForServices() throws InterruptedException {
        List<String> all = new ArrayList<>(s.ingestionUrls());
        all.addAll(s.indexingUrls());
        long deadline = System.currentTimeMillis() + 120_000;
        for (String base : all) {
            while (true) {
                try {
                    if (http.get(base + "/status").ok()) break;
                } catch (IOException ignored) {
                    // todavía arrancando
                }
                if (System.currentTimeMillis() > deadline)
                    throw new IllegalStateException("El servicio " + base + " no responde a GET /status");
                Thread.sleep(500);
            }
        }
    }
}
