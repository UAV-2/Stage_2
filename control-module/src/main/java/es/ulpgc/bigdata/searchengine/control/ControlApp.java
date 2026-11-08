package es.ulpgc.bigdata.searchengine.control;

import es.ulpgc.bigdata.searchengine.common.Config;
import es.ulpgc.bigdata.searchengine.common.http.ApiException;
import es.ulpgc.bigdata.searchengine.common.http.Json;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import es.ulpgc.bigdata.searchengine.common.http.ServiceServer;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Control Module (orquestador). Dos modos:
 *
 *   java -jar control-module.jar --books 100    procesa 100 libros nuevos y termina
 *   java -jar control-module.jar                API de administración en el puerto 7000:
 *        POST /control/run?books=N   lanza una ejecución en segundo plano (202)
 *        GET  /control/status        progreso y métricas de la última ejecución
 *
 * Variables: DATA_DIR, BOOK_IDS (shared/book_ids.txt), INGESTION_URLS (http://localhost:7001),
 * INDEXING_URLS (http://localhost:7002), WORKERS (4), PUBLISH_BATCH (25),
 * PUBLISH_INTERVAL_MS (2000), MAX_RETRIES (3), RETRY_BACKOFF_MS (500), REQUEST_TIMEOUT_MS (120000).
 */
public final class ControlApp {
    private ControlApp() {}

    public static void main(String[] args) throws Exception {
        Orchestrator orchestrator = create();
        List<Integer> ids = readIds(Config.path("BOOK_IDS", "shared/book_ids.txt"));
        Integer books = null;
        for (int i = 0; i < args.length; i++) if ("--books".equals(args[i]) && i + 1 < args.length) books = Integer.parseInt(args[++i]);
        if (books != null) {
            RunMetrics m = orchestrator.run(ids, books);
            saveReport(m);
            System.out.println(Json.PRETTY.toJson(m.toMap()));
            System.exit(m.gaveUp.get() == 0 ? 0 : 2);
        }
        startAdmin(orchestrator, ids);
    }

    static Orchestrator create() throws IOException {
        Orchestrator.Settings s = new Orchestrator.Settings(
                Config.list("INGESTION_URLS", "http://localhost:7001"),
                Config.list("INDEXING_URLS", "http://localhost:7002"),
                Config.integer("WORKERS", 4),
                Config.integer("PUBLISH_BATCH", 25),
                Config.integer("PUBLISH_INTERVAL_MS", 2000),
                Config.integer("MAX_RETRIES", 3),
                Config.integer("RETRY_BACKOFF_MS", 500));
        ServiceClient http = new ServiceClient(Duration.ofMillis(Config.integer("REQUEST_TIMEOUT_MS", 120_000)));
        return new Orchestrator(s, new ControlState(Config.dataDir().resolve("control")), http);
    }

    public static Javalin startAdmin(Orchestrator orchestrator, List<Integer> ids) {
        ServiceServer server = new ServiceServer("control-module", 7000);
        AtomicReference<Thread> running = new AtomicReference<>();
        return server.start(app -> {
            app.post("/control/run", ctx -> {
                int books;
                try {
                    books = Integer.parseInt(ctx.queryParamAsClass("books", String.class).getOrDefault("10"));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("INVALID_BOOKS", "'books' debe ser un entero positivo");
                }
                if (books <= 0) throw ApiException.badRequest("INVALID_BOOKS", "'books' debe ser un entero positivo");
                Thread t = new Thread(() -> {
                    try {
                        saveReport(orchestrator.run(ids, books));
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(ControlApp.class).error("Ejecución fallida", e);
                    } finally {
                        running.set(null);
                    }
                }, "control-run");
                if (!running.compareAndSet(null, t))
                    throw new ApiException(409, "RUN_IN_PROGRESS", "Ya hay una ejecución en curso");
                t.start();
                ctx.status(202).json(Map.of("status", "accepted", "books", books,
                        "message", "Consulta el progreso en GET /control/status"));
            });
            app.get("/control/status", ctx -> ctx.json(status(orchestrator)));
        }, () -> status(orchestrator));
    }

    private static Map<String, Object> status(Orchestrator o) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("phase", o.phase());
        RunMetrics m = o.metrics();
        s.put("last_run", m == null ? null : m.toMap());
        return s;
    }

    static List<Integer> readIds(Path file) throws IOException {
        List<Integer> ids = new ArrayList<>();
        if (!Files.exists(file)) return ids;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            l = l.strip();
            if (!l.isEmpty() && !l.startsWith("#")) ids.add(Integer.parseInt(l));
        }
        return ids;
    }

    private static void saveReport(RunMetrics m) throws IOException {
        Path dir = Config.dataDir().resolve("control/runs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(m.runId.replace(':', '-') + ".json"), Json.PRETTY.toJson(m.toMap()));
    }
}
