package es.ulpgc.bigdata.searchengine.ingestion;

import es.ulpgc.bigdata.searchengine.common.Config;
import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.http.ServiceServer;
import io.javalin.Javalin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingestion Service. Rutas internas (las consume el Control Module, no el front-end):
 *
 *   POST /ingest/{book_id}         descarga y guarda el libro (síncrono, idempotente)
 *   GET  /ingest/status/{book_id}  disponible o no
 *   GET  /ingest/list              libros disponibles en el datalake
 *   GET  /status                   salud y estadísticas (operacional)
 *
 * Variables: PORT (7001), DATA_DIR (data), SOURCE (gutenberg | local:&lt;dir&gt; | URL con {id}),
 * SOURCE_DELAY_MS (1000), HTTP_THREADS, REPLICA_ID.
 */
public final class IngestionApp {
    private IngestionApp() {}

    public static void main(String[] args) {
        Datalake lake = new Datalake(Config.dataDir().resolve("datalake"));
        BookSource source = BookSource.fromSpec(Config.str("SOURCE", "gutenberg"), Config.integer("SOURCE_DELAY_MS", 1000));
        start(new IngestionService(lake, source));
    }

    public static Javalin start(IngestionService service) {
        ServiceServer server = new ServiceServer("ingestion-service", 7001);
        return server.start(app -> {
            app.post("/ingest/{book_id}", ctx -> {
                var r = service.ingest(ServiceServer.pathInt(ctx, "book_id"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("book_id", r.bookId());
                body.put("status", r.status());
                body.put("path", r.path());
                body.put("already_present", r.alreadyPresent());
                body.put("elapsed_ms", r.elapsedMs());
                body.put("replica_id", server.replicaId());
                ctx.json(body);
            });
            app.get("/ingest/status/{book_id}", ctx -> ctx.json(service.status(ServiceServer.pathInt(ctx, "book_id"))));
            app.get("/ingest/list", ctx -> {
                List<Integer> books = service.list();
                ctx.json(Map.of("count", books.size(), "books", books));
            });
        }, service::stats);
    }
}
