package es.ulpgc.bigdata.searchengine.search;

import es.ulpgc.bigdata.searchengine.common.Config;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ServiceServer;
import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Search Service. API para el front-end:
 *
 *   GET /search?q=&amp;author=&amp;language=&amp;year=&amp;offset=&amp;limit=
 *   GET /books/{book_id}
 *   GET /search/filters
 *   GET /status
 *
 * Solo LEE datamarts (índice publicado + metadatos), así que se puede replicar libremente.
 * Variables: PORT (7003), DATA_DIR, STOPWORDS, REFRESH_MS (cada cuánto mira si hay una
 * versión nueva del índice, 500), CACHE_ENTRIES (50000), HTTP_THREADS, REPLICA_ID.
 */
public final class SearchApp {
    private static final Logger LOG = LoggerFactory.getLogger(SearchApp.class);

    private SearchApp() {}

    public static void main(String[] args) {
        Path data = Config.dataDir();
        IndexReader reader = new IndexReader(new InvertedIndex(data.resolve("datamarts/index")),
                Config.integer("CACHE_ENTRIES", 50_000));
        SearchEngine engine = new SearchEngine(reader, new MetadataStore(data.resolve("datamarts/metadata.db")),
                Tokenizer.fromFile(Config.stopwordsFile()));
        startRefresher(reader, Config.integer("REFRESH_MS", 500));
        start(engine);
    }

    /** Hilo que detecta versiones nuevas del índice publicado (lee manifest.json). */
    public static ScheduledExecutorService startRefresher(IndexReader reader, int periodMs) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "index-refresher");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(() -> {
            try {
                long before = reader.snapshot().version();
                long after = reader.refresh().version();
                if (after != before) LOG.info("Índice publicado: versión {} -> {}", before, after);
            } catch (RuntimeException e) {
                LOG.warn("No se pudo refrescar el índice: {}", e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return s;
    }

    public static Javalin start(SearchEngine engine) {
        ServiceServer server = new ServiceServer("search-service", 7003);
        return server.start(app -> {
            app.get("/search", ctx -> {
                Map<String, String> p = new HashMap<>();
                for (String k : new String[]{"q", "author", "language", "year", "offset", "limit"}) p.put(k, ctx.queryParam(k));
                ctx.json(engine.search(SearchQuery.parse(p)));
            });
            app.get("/search/filters", ctx -> ctx.json(engine.filters()));
            app.get("/books/{book_id}", ctx -> ctx.json(engine.bookDetails(ServiceServer.pathInt(ctx, "book_id"))));
        }, engine::stats);
    }
}
