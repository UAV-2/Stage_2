package es.ulpgc.bigdata.searchengine.indexing;

import static org.junit.jupiter.api.Assertions.*;

import es.ulpgc.bigdata.searchengine.common.Datalake;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import es.ulpgc.bigdata.searchengine.common.http.ServiceServer;
import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingApiTest {
    @TempDir
    Path tmp;

    private Javalin app;
    private String base;
    private InvertedIndex index;
    private MetadataStore meta;
    private final ServiceClient http = new ServiceClient(Duration.ofSeconds(10));

    @BeforeEach
    void start() throws Exception {
        Datalake lake = new Datalake(tmp.resolve("datalake"));
        lake.store(5, "Title: Robinson Crusoe\nAuthor: Daniel Defoe\nLanguage: English\nRelease date: May 1, 1996",
                "The island. The ISLAND and the sea.");
        lake.store(1342, "Title: Pride and Prejudice\nAuthor: Jane Austen\nLanguage: English", "A truth about the sea.");
        index = new InvertedIndex(tmp.resolve("datamarts/index"));
        meta = new MetadataStore(tmp.resolve("datamarts/metadata.db"));
        System.setProperty("PORT", "0");
        ServiceServer server = new ServiceServer("indexing-service", 7002);
        app = IndexingApp.start(server, new IndexingService(lake, meta, index, new Tokenizer(Set.of("the", "and", "a")),
                "test", false, 16, 2));
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
        meta.close();
        System.clearProperty("PORT");
    }

    @Test
    void updateIsNotVisibleUntilPublish() throws Exception {
        var u = http.post(base + "/index/update/5");
        assertEquals(200, u.status(), u.raw());
        assertEquals("updated", u.str("index"));
        assertFalse(u.body().get("published").getAsBoolean());
        assertEquals("Daniel Defoe", meta.get(5).orElseThrow().author());

        IndexReader reader = new IndexReader(index, 100);
        assertTrue(reader.postings(reader.refresh(), "island").isEmpty());

        var p = http.post(base + "/index/publish");
        assertEquals(1, p.body().get("version").getAsInt());
        assertEquals(Map.of(5, 2), reader.postings(reader.refresh(), "island"));

        var again = http.post(base + "/index/update/5"); // idempotente
        assertTrue(again.body().get("already_indexed").getAsBoolean());

        var st = http.get(base + "/index/status");
        assertEquals(1, st.body().get("books_indexed").getAsInt());
        assertEquals(0, st.body().get("pending_books").getAsInt());
    }

    @Test
    void rebuildAndErrors() throws Exception {
        var r = http.post(base + "/index/rebuild");
        assertEquals(2, r.body().get("books_processed").getAsInt());
        assertTrue(r.str("elapsed_time").endsWith("s"));
        assertEquals(2, http.get(base + "/index/status").body().get("books_indexed").getAsInt());

        var missing = http.post(base + "/index/update/999");
        assertEquals(409, missing.status());
        assertEquals("BOOK_NOT_INGESTED", missing.str("error"));
        assertEquals(400, http.post(base + "/index/update/x").status());
    }
}
