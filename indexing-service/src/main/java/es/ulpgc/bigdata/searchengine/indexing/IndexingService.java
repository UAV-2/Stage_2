package es.ulpgc.bigdata.searchengine.indexing;

import es.ulpgc.bigdata.searchengine.common.BookMetadata;
import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.HeaderParser;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ApiException;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import es.ulpgc.bigdata.searchengine.common.index.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lógica del servicio de indexación.
 *
 * Flujo de un libro:
 *   1. update(id): lee el libro del datalake, guarda sus metadatos (upsert) y deja sus
 *      términos en un buffer de pendientes de ESTA réplica. Todavía no es buscable.
 *   2. publish(): escribe un segmento con todos los pendientes y lo publica en el manifest
 *      de forma atómica. A partir de ahí la búsqueda lo ve, con todos sus términos a la vez.
 *
 * Con PUBLISH_MODE=immediate cada update publica en el acto (un segmento por libro).
 */
public final class IndexingService {
    private static final Logger LOG = LoggerFactory.getLogger(IndexingService.class);

    private final Datalake datalake;
    private final MetadataStore metadata;
    private final InvertedIndex index;
    private final Tokenizer tokenizer;
    private final String replicaId;
    private final boolean immediate;
    private final int compactThreshold;
    private final int workerThreads;

    private final Map<Integer, Map<String, Integer>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock publishLock = new ReentrantLock();
    private final ExecutorService compactor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-compactor");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean compacting = new AtomicBoolean(false);

    public IndexingService(Datalake datalake, MetadataStore metadata, InvertedIndex index, Tokenizer tokenizer,
                           String replicaId, boolean immediate, int compactThreshold, int workerThreads) {
        this.datalake = datalake;
        this.metadata = metadata;
        this.index = index;
        this.tokenizer = tokenizer;
        this.replicaId = replicaId;
        this.immediate = immediate;
        this.compactThreshold = compactThreshold;
        this.workerThreads = Math.max(1, workerThreads);
    }

    public Map<String, Object> update(int bookId) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("book_id", bookId);
        if (index.readManifest().books.contains(bookId)) { // idempotente: ya es buscable
            out.put("index", "updated");
            out.put("published", true);
            out.put("already_indexed", true);
            out.put("pending_books", pending.size());
            return out;
        }
        Prepared p = prepare(bookId);
        metadata.upsertAll(List.of(p.metadata()));
        pending.put(bookId, p.terms());
        out.put("index", "updated");
        out.put("terms", p.terms().size());
        if (immediate) {
            Map<String, Object> pub = publish();
            out.put("published", ((List<?>) pub.get("published")).contains(bookId)
                    || ((List<?>) pub.get("skipped")).contains(bookId));
            out.put("version", pub.get("version"));
        } else {
            out.put("published", false);
        }
        out.put("already_indexed", false);
        out.put("pending_books", pending.size());
        return out;
    }

    /** Publica todos los pendientes de esta réplica en un solo segmento. */
    public Map<String, Object> publish() throws IOException {
        publishLock.lock();
        try {
            Map<Integer, Map<String, Integer>> batch = new HashMap<>(pending);
            InvertedIndex.PublishResult r = index.publish(batch, replicaId);
            batch.keySet().forEach(pending::remove);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", r.version());
            out.put("published", r.published());
            out.put("skipped", r.skipped());
            out.put("segment", r.segment());
            out.put("pending_books", pending.size());
            maybeCompact();
            return out;
        } finally {
            publishLock.unlock();
        }
    }

    /** Reconstruye índice y metadatos desde cero con todo el datalake (tokenización en paralelo). */
    public Map<String, Object> rebuild() throws IOException {
        publishLock.lock();
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(workerThreads);
        try {
            List<Integer> ids = datalake.listBooks();
            List<Future<Prepared>> fs = new ArrayList<>();
            for (int id : ids) fs.add(pool.submit(() -> prepare(id)));
            Map<Integer, Map<String, Integer>> all = new HashMap<>();
            List<BookMetadata> metas = new ArrayList<>();
            for (Future<Prepared> f : fs) {
                Prepared p = get(f);
                all.put(p.metadata().bookId(), p.terms());
                metas.add(p.metadata());
            }
            metadata.upsertAll(metas);
            InvertedIndex.PublishResult r = index.rebuild(all, replicaId);
            pending.clear();
            double secs = (System.nanoTime() - t0) / 1e9;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("books_processed", ids.size());
            out.put("elapsed_time", String.format(java.util.Locale.ROOT, "%.1fs", secs));
            out.put("version", r.version());
            out.put("worker_threads", workerThreads);
            return out;
        } finally {
            pool.shutdown();
            publishLock.unlock();
        }
    }

    public Map<String, Object> compactNow() throws IOException {
        InvertedIndex.CompactResult c = index.compact(replicaId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compacted", c != null);
        if (c != null) {
            out.put("version", c.version());
            out.put("merged_segments", c.mergedSegments());
            out.put("terms", c.terms());
        }
        return out;
    }

    public Map<String, Object> status() {
        try {
            Manifest m = index.readManifest();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("books_indexed", m.books.size());
            s.put("last_update", m.updatedAt);
            s.put("index_size_MB", Math.round(index.sizeBytes() / 1048576.0 * 10) / 10.0);
            s.put("version", m.version);
            s.put("segments", m.segments.size());
            s.put("pending_books", pending.size());
            s.put("metadata_rows", metadata.count());
            s.put("publish_mode", immediate ? "immediate" : "batch");
            s.put("replica_id", replicaId);
            s.put("worker_threads", workerThreads);
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void maybeCompact() throws IOException {
        if (compactThreshold <= 0 || index.readManifest().segments.size() <= compactThreshold) return;
        if (!compacting.compareAndSet(false, true)) return;
        compactor.submit(() -> {
            try {
                var c = index.compact(replicaId);
                if (c != null) LOG.info("Compactación: {} segmentos fusionados (versión {})", c.mergedSegments(), c.version());
            } catch (Exception e) {
                LOG.warn("Compactación fallida", e);
            } finally {
                compacting.set(false);
            }
        });
    }

    private record Prepared(BookMetadata metadata, Map<String, Integer> terms) {}

    private Prepared prepare(int bookId) throws IOException {
        Datalake.Location loc = datalake.locate(bookId).orElseThrow(() -> new ApiException(409, "BOOK_NOT_INGESTED",
                "El libro " + bookId + " no está disponible en el datalake; ingiéralo primero"));
        BookMetadata meta = HeaderParser.parse(bookId, datalake.readHeader(loc)).withBookIdAndPath(bookId, loc.apiPath());
        return new Prepared(meta, tokenizer.termFrequencies(datalake.readBody(loc)));
    }

    private static <T> T get(Future<T> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IOException(e.getCause());
        }
    }
}
