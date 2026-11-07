package es.ulpgc.bigdata.searchengine.ingestion;

import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.GutenbergText;
import es.ulpgc.bigdata.searchengine.common.http.ApiException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Lógica del servicio de ingesta, independiente de HTTP (se prueba y se mide aparte). */
public final class IngestionService {
    private final Datalake datalake;
    private final BookSource source;
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong alreadyPresent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public record Result(int bookId, String status, String path, boolean alreadyPresent, long elapsedMs) {}

    public IngestionService(Datalake datalake, BookSource source) {
        this.datalake = datalake;
        this.source = source;
    }

    /**
     * Descarga el libro y lo guarda en el datalake. Es síncrono: cuando devuelve, el libro
     * ya está disponible (header y body escritos y registrados en _locations.tsv).
     * Idempotente: si el libro ya estaba, no se vuelve a descargar.
     */
    public Result ingest(int bookId) throws IOException {
        long t0 = System.nanoTime();
        Optional<Datalake.Location> existing = datalake.locate(bookId);
        if (existing.isPresent()) {
            alreadyPresent.incrementAndGet();
            return new Result(bookId, "downloaded", existing.get().apiPath(), true, ms(t0));
        }
        byte[] raw;
        try {
            raw = source.fetch(bookId);
        } catch (IOException e) {
            failed.incrementAndGet();
            throw new ApiException(502, "UPSTREAM_ERROR", "No se pudo descargar el libro " + bookId + ": " + e.getMessage());
        }
        if (raw == null) {
            failed.incrementAndGet();
            throw ApiException.notFound("BOOK_NOT_FOUND", "El libro " + bookId + " no existe en el origen");
        }
        var parts = GutenbergText.split(raw);
        if (parts.isEmpty()) {
            failed.incrementAndGet();
            throw new ApiException(422, "INVALID_BOOK_FORMAT",
                    "El libro " + bookId + " no tiene los marcadores START/END de Project Gutenberg");
        }
        Datalake.StoreResult r = datalake.store(bookId, parts.get().header(), parts.get().body());
        if (r.alreadyPresent()) alreadyPresent.incrementAndGet(); else downloaded.incrementAndGet();
        return new Result(bookId, "downloaded", r.location().apiPath(), r.alreadyPresent(), ms(t0));
    }

    public Map<String, Object> status(int bookId) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Datalake.Location> loc = datalake.locate(bookId);
        out.put("book_id", bookId);
        out.put("status", loc.isPresent() ? "available" : "not_available");
        out.put("path", loc.map(Datalake.Location::apiPath).orElse(null));
        return out;
    }

    public List<Integer> list() throws IOException {
        return datalake.listBooks();
    }

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        try {
            s.put("books_available", datalake.listBooks().size());
        } catch (IOException e) {
            s.put("books_available", null);
        }
        s.put("downloaded_by_this_replica", downloaded.get());
        s.put("already_present_requests", alreadyPresent.get());
        s.put("failed_requests", failed.get());
        s.put("source", source.describe());
        return s;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
