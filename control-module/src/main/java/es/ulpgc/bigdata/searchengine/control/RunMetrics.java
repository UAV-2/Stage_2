package es.ulpgc.bigdata.searchengine.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Métricas de una ejecución del flujo (throughput, latencias y coste de coordinación). */
public final class RunMetrics {
    final String runId = Instant.now().toString();
    final long startNanos = System.nanoTime();
    volatile long endNanos = 0;
    final AtomicInteger ingested = new AtomicInteger();
    final AtomicInteger alreadyIngested = new AtomicInteger();
    final AtomicInteger published = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();
    final AtomicInteger retries = new AtomicInteger();
    final AtomicInteger gaveUp = new AtomicInteger();
    final AtomicInteger publishCalls = new AtomicInteger();
    final AtomicInteger redispatched = new AtomicInteger();
    final List<Long> ingestMs = Collections.synchronizedList(new ArrayList<>());
    final List<Long> updateMs = Collections.synchronizedList(new ArrayList<>());
    final List<Long> publishMs = Collections.synchronizedList(new ArrayList<>());
    /** Tiempo que un libro ya indexado espera a ser publicado (coste de la coordinación por lotes). */
    final List<Long> publishWaitMs = Collections.synchronizedList(new ArrayList<>());
    volatile int requested = 0;
    volatile int recovered = 0;

    public Map<String, Object> toMap() {
        double secs = ((endNanos == 0 ? System.nanoTime() : endNanos) - startNanos) / 1e9;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("finished", endNanos != 0);
        m.put("elapsed_s", round(secs));
        m.put("books_requested", requested);
        m.put("books_recovered_from_previous_run", recovered);
        m.put("books_ingested", ingested.get());
        m.put("books_already_in_datalake", alreadyIngested.get());
        m.put("books_published", published.get());
        m.put("books_failed", failed.get());
        m.put("books_given_up_after_retries", gaveUp.get());
        m.put("retries", retries.get());
        m.put("publish_calls", publishCalls.get());
        m.put("books_redispatched", redispatched.get());
        m.put("ingestion_throughput_books_s", secs > 0 ? round(ingested.get() / secs) : null);
        m.put("indexing_throughput_books_s", secs > 0 ? round(published.get() / secs) : null);
        m.put("ingest_ms", stats(ingestMs));
        m.put("index_update_ms", stats(updateMs));
        m.put("publish_call_ms", stats(publishMs));
        m.put("publish_wait_ms", stats(publishWaitMs));
        return m;
    }

    static Map<String, Object> stats(List<Long> values) {
        List<Long> v;
        synchronized (values) {
            v = new ArrayList<>(values);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("count", v.size());
        if (v.isEmpty()) return s;
        Collections.sort(v);
        s.put("mean", round(v.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("p50", v.get((int) Math.floor(0.50 * (v.size() - 1))));
        s.put("p95", v.get((int) Math.floor(0.95 * (v.size() - 1))));
        s.put("max", v.get(v.size() - 1));
        return s;
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
