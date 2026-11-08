package es.ulpgc.bigdata.searchengine.control;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import io.javalin.Javalin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Orquestador contra servicios falsos (Javalin en memoria) para probar el flujo, los
 * reintentos, los fallos permanentes y la reanudación sin depender de los servicios reales.
 */
class OrchestratorTest {
    @TempDir
    Path tmp;

    private Javalin fake;
    private final Set<Integer> datalake = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pending = ConcurrentHashMap.newKeySet();
    private final Set<Integer> published = ConcurrentHashMap.newKeySet();
    private final AtomicInteger flakyIngest = new AtomicInteger(0);

    private String startFake() {
        fake = Javalin.create(c -> { c.showJavalinBanner = false; c.jsonMapper(es.ulpgc.bigdata.searchengine.common.http.Json.javalinMapper()); });
        fake.get("/status", ctx -> ctx.json(Map.of("status", "running")));
        fake.post("/ingest/{id}", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            if (id == 404) { ctx.status(404).json(Map.of("error", "BOOK_NOT_FOUND")); return; }
            if (id == 13 && flakyIngest.getAndIncrement() < 2) { ctx.status(502).json(Map.of("error", "UPSTREAM_ERROR")); return; }
            boolean already = !datalake.add(id);
            ctx.json(Map.of("book_id", id, "status", "downloaded", "already_present", already));
        });
        fake.post("/index/update/{id}", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            if (!datalake.contains(id)) { ctx.status(409).json(Map.of("error", "BOOK_NOT_INGESTED")); return; }
            boolean already = published.contains(id);
            if (!already) pending.add(id);
            ctx.json(Map.of("book_id", id, "index", "updated", "published", already, "already_indexed", already));
        });
        fake.post("/index/publish", ctx -> {
            List<Integer> batch = List.copyOf(pending);
            published.addAll(batch);
            pending.removeAll(batch);
            ctx.json(Map.of("version", 1, "published", batch, "skipped", List.of()));
        });
        fake.start(0);
        return "http://localhost:" + fake.port();
    }

    @AfterEach
    void stop() {
        if (fake != null) fake.stop();
    }

    private Orchestrator orchestrator(String base) throws Exception {
        return new Orchestrator(new Orchestrator.Settings(List.of(base), List.of(base), 3, 2, 50, 3, 10),
                new ControlState(tmp.resolve("control")), new ServiceClient(Duration.ofSeconds(5)));
    }

    @Test
    void processesBooksRetriesAndRecordsState() throws Exception {
        String base = startFake();
        RunMetrics m = orchestrator(base).run(List.of(1, 2, 13, 404, 5, 6), 10);
        assertEquals(5, m.published.get());
        assertEquals(1, m.failed.get());
        assertTrue(m.retries.get() >= 2);
        assertEquals(Set.of(1, 2, 13, 5, 6), published);
        Path ctl = tmp.resolve("control");
        assertEquals(5, Files.readAllLines(ctl.resolve("indexed_books.txt")).size());
        assertTrue(Files.readString(ctl.resolve("failed_books.txt")).startsWith("404\t404"));
    }

    @Test
    void respectsLimitAndResumesWithoutDuplicates() throws Exception {
        String base = startFake();
        orchestrator(base).run(List.of(1, 2, 3, 4), 2);
        assertEquals(Set.of(1, 2), published);
        // simula un corte: el 3 se descargó pero el control no llegó a indexarlo
        datalake.add(3);
        Files.writeString(tmp.resolve("control/downloaded_books.txt"), "3\n", java.nio.file.StandardOpenOption.APPEND);
        RunMetrics m = orchestrator(base).run(List.of(1, 2, 3, 4), 10);
        assertEquals(1, m.recovered);
        assertEquals(Set.of(1, 2, 3, 4), published);
        List<String> lines = Files.readAllLines(tmp.resolve("control/indexed_books.txt"));
        assertEquals(4, lines.size());
        assertEquals(4, Set.copyOf(lines).size());
    }

    @Test
    void adminApiStartsRuns() throws Exception {
        String base = startFake();
        System.setProperty("PORT", "0");
        Javalin admin = ControlApp.startAdmin(orchestrator(base), List.of(7, 8));
        try {
            ServiceClient http = new ServiceClient(Duration.ofSeconds(5));
            String url = "http://localhost:" + admin.port();
            assertEquals(400, http.post(url + "/control/run?books=0").status());
            assertEquals(202, http.post(url + "/control/run?books=5").status());
            JsonObject st = null;
            for (int i = 0; i < 100; i++) {
                st = http.get(url + "/control/status").body();
                if ("idle".equals(st.get("phase").getAsString()) && !st.get("last_run").isJsonNull()
                        && st.getAsJsonObject("last_run").get("finished").getAsBoolean()) break;
                Thread.sleep(50);
            }
            assertEquals(2, st.getAsJsonObject("last_run").get("books_published").getAsInt());
        } finally {
            admin.stop();
            System.clearProperty("PORT");
        }
    }
}
