package es.ulpgc.bigdata.searchengine.indexing;

import es.ulpgc.bigdata.searchengine.common.Config;
import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ServiceServer;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import io.javalin.Javalin;
import java.nio.file.Path;

/**
 * Indexing Service. Rutas internas (las consume el Control Module) y operacionales:
 *
 *   POST /index/update/{book_id}  metadatos + términos del libro al buffer de pendientes
 *   POST /index/publish           publica los pendientes (nueva versión del índice)
 *   POST /index/rebuild           reconstruye todo desde el datalake (administración)
 *   POST /index/compact           fusiona segmentos (administración)
 *   GET  /index/status            estadísticas del índice
 *   GET  /status                  salud
 *
 * Variables: PORT (7002), DATA_DIR, STOPWORDS, PUBLISH_MODE (batch | immediate),
 * COMPACT_THRESHOLD (16 segmentos), INDEX_THREADS (hilos del rebuild), HTTP_THREADS, REPLICA_ID.
 */
public final class IndexingApp {
    private IndexingApp() {}

    public static void main(String[] args) {
        Path data = Config.dataDir();
        ServiceServer server = new ServiceServer("indexing-service", 7002);
        IndexingService service = new IndexingService(
                new Datalake(data.resolve("datalake")),
                new MetadataStore(data.resolve("datamarts/metadata.db")),
                new InvertedIndex(data.resolve("datamarts/index"), Config.integer("RETIRED_GRACE_MS", 60_000)),
                Tokenizer.fromFile(Config.stopwordsFile()),
                server.replicaId(),
                "immediate".equalsIgnoreCase(Config.str("PUBLISH_MODE", "batch")),
                Config.integer("COMPACT_THRESHOLD", 16),
                Config.integer("INDEX_THREADS", Runtime.getRuntime().availableProcessors()));
        start(server, service);
    }

    public static Javalin start(ServiceServer server, IndexingService service) {
        return server.start(app -> {
            app.post("/index/update/{book_id}", ctx -> ctx.json(service.update(ServiceServer.pathInt(ctx, "book_id"))));
            app.post("/index/publish", ctx -> ctx.json(service.publish()));
            app.post("/index/rebuild", ctx -> ctx.json(service.rebuild()));
            app.post("/index/compact", ctx -> ctx.json(service.compactNow()));
            app.get("/index/status", ctx -> ctx.json(service.status()));
        }, service::status);
    }
}
