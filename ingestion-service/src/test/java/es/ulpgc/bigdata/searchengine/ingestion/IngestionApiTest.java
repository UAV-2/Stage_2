package es.ulpgc.bigdata.searchengine.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import io.javalin.Javalin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Comprueba el contrato HTTP del servicio arrancándolo en un puerto libre. */
class IngestionApiTest {
    @TempDir
    Path tmp;

    private Javalin app;
    private String base;
    private final ServiceClient http = new ServiceClient(Duration.ofSeconds(10));

    @BeforeEach
    void start() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("1342.txt"), "Title: Pride and Prejudice\nAuthor: Jane Austen\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK PRIDE ***\nIt is a truth.\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK PRIDE ***\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("pg77.txt"), "no markers", StandardCharsets.UTF_8);
        System.setProperty("PORT", "0");
        IngestionService service = new IngestionService(new Datalake(tmp.resolve("datalake")),
                BookSource.fromSpec("local:" + src, 0));
        app = IngestionApp.start(service);
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
        System.clearProperty("PORT");
    }

    @Test
    void ingestStatusAndList() throws Exception {
        var r = http.post(base + "/ingest/1342");
        assertEquals(200, r.status(), r.raw());
        assertEquals("downloaded", r.str("status"));
        assertTrue(r.str("path").matches("datalake/\\d{8}/\\d{2}/1342"));
        assertFalse(r.body().get("already_present").getAsBoolean());

        var again = http.post(base + "/ingest/1342");
        assertTrue(again.body().get("already_present").getAsBoolean());

        assertEquals("available", http.get(base + "/ingest/status/1342").str("status"));
        assertEquals("not_available", http.get(base + "/ingest/status/5").str("status"));
        var list = http.get(base + "/ingest/list");
        assertEquals(1, list.body().get("count").getAsInt());
        assertEquals(1342, list.body().getAsJsonArray("books").get(0).getAsInt());
    }

    @Test
    void documentedErrors() throws Exception {
        assertEquals(404, http.post(base + "/ingest/999").status());
        assertEquals("BOOK_NOT_FOUND", http.post(base + "/ingest/999").str("error"));
        assertEquals(422, http.post(base + "/ingest/77").status());
        assertEquals("INVALID_BOOK_ID", http.post(base + "/ingest/abc").str("error"));
        assertEquals(400, http.post(base + "/ingest/-3").status());
        assertEquals(404, http.get(base + "/nope").status());
        assertEquals("running", http.get(base + "/status").str("status"));
    }
}
