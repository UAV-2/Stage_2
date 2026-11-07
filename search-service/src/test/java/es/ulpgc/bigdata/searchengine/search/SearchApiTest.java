package es.ulpgc.bigdata.searchengine.search;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import es.ulpgc.bigdata.searchengine.common.BookMetadata;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ServiceClient;
import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contrato de la API del front-end: búsqueda, filtros, paginación, vacíos y errores. */
class SearchApiTest {
    @TempDir
    Path tmp;

    private Javalin app;
    private String base;
    private MetadataStore meta;
    private InvertedIndex index;
    private IndexReader reader;
    private final ServiceClient http = new ServiceClient(Duration.ofSeconds(10));

    @BeforeEach
    void start() throws Exception {
        index = new InvertedIndex(tmp.resolve("index"));
        meta = new MetadataStore(tmp.resolve("metadata.db"));
        meta.upsertAll(List.of(
                new BookMetadata(5, "Robinson Crusoe", "Daniel Defoe", "en", 1719, "original_publication", null, "p"),
                new BookMetadata(1342, "Pride and Prejudice", "Jane Austen", "en", 1813, "original_publication", null, "p"),
                new BookMetadata(17, "Le Tour du Monde", "Jules Verne", "fr", 1872, "original_publication", null, "p")));
        Map<Integer, Map<String, Integer>> docs = new HashMap<>();
        docs.put(5, Map.of("adventure", 3, "island", 5));
        docs.put(1342, Map.of("adventure", 1, "love", 4));
        docs.put(17, Map.of("adventure", 2, "island", 1));
        for (int i = 100; i < 130; i++) docs.put(i, Map.of("common", 1)); // para paginar
        index.publish(docs, "t");
        reader = new IndexReader(index, 1000);
        System.setProperty("PORT", "0");
        app = SearchApp.start(new SearchEngine(reader, meta, new Tokenizer(Set.of("the"))));
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
        meta.close();
        System.clearProperty("PORT");
    }

    private JsonObject ok(String path) throws Exception {
        var r = http.get(base + path);
        assertEquals(200, r.status(), r.raw());
        return r.body();
    }

    private List<Integer> ids(JsonObject body) {
        List<Integer> out = new ArrayList<>();
        body.getAsJsonArray("results").forEach(e -> out.add(e.getAsJsonObject().get("book_id").getAsInt()));
        return out;
    }

    @Test
    void keywordSearchRankedByRelevance() throws Exception {
        JsonObject b = ok("/search?q=adventure");
        assertEquals(3, b.get("total").getAsInt());
        assertEquals(List.of(5, 17, 1342), ids(b)); // tf 3 > 2 > 1
        assertEquals("Robinson Crusoe", b.getAsJsonArray("results").get(0).getAsJsonObject().get("title").getAsString());
        assertEquals(List.of(5, 17), ids(ok("/search?q=Adventure%20ISLAND%20the"))); // AND, mayúsculas y stopwords
    }

    @Test
    void individualAndCombinedFilters() throws Exception {
        assertEquals(List.of(5), ids(ok("/search?q=adventure&author=defoe")));
        assertEquals(List.of(17), ids(ok("/search?q=adventure&language=fr")));
        assertEquals(List.of(1342), ids(ok("/search?q=adventure&year=1813")));
        assertEquals(List.of(5), ids(ok("/search?q=adventure&language=en&year=1719&author=Daniel")));
        JsonObject f = ok("/search?q=adventure&language=EN");
        assertEquals("en", f.getAsJsonObject("filters").get("language").getAsString());
    }

    @Test
    void emptyResultsAre200() throws Exception {
        JsonObject b = ok("/search?q=zebra");
        assertEquals(0, b.get("total").getAsInt());
        assertEquals(0, b.getAsJsonArray("results").size());
        assertEquals(0, ok("/search?q=adventure&language=de").get("total").getAsInt());
        assertEquals(0, ok("/search?q=the").get("total").getAsInt()); // solo stopwords
    }

    @Test
    void pagination() throws Exception {
        JsonObject p1 = ok("/search?q=common&limit=20");
        JsonObject p2 = ok("/search?q=common&offset=20&limit=20");
        assertEquals(30, p1.get("total").getAsInt());
        assertEquals(20, ids(p1).size());
        assertEquals(10, ids(p2).size());
        assertEquals(100, ids(p1).get(0)); // empate de score -> orden por book_id
        assertEquals(120, ids(p2).get(0));
        assertEquals(0, ids(ok("/search?q=common&offset=100")).size());
        assertEquals(20, ok("/search?q=common").get("limit").getAsInt()); // valor por defecto
    }

    @Test
    void invalidParameters() throws Exception {
        String[][] cases = {{"/search", "MISSING_QUERY"}, {"/search?q=%20", "MISSING_QUERY"},
                {"/search?q=a&limit=0", "INVALID_LIMIT"}, {"/search?q=a&limit=101", "INVALID_LIMIT"},
                {"/search?q=a&limit=x", "INVALID_LIMIT"}, {"/search?q=a&offset=-1", "INVALID_OFFSET"},
                {"/search?q=a&year=17", "INVALID_YEAR"}, {"/search?q=a&language=e1", "INVALID_LANGUAGE"}};
        for (String[] c : cases) {
            var r = http.get(base + c[0]);
            assertEquals(400, r.status(), c[0]);
            assertEquals(c[1], r.str("error"), c[0]);
        }
    }

    @Test
    void bookDetailsAndFilterValues() throws Exception {
        JsonObject b = ok("/books/1342");
        assertEquals("Jane Austen", b.get("author").getAsString());
        assertEquals(404, http.get(base + "/books/999").status());
        assertEquals(400, http.get(base + "/books/abc").status());
        JsonObject f = ok("/search/filters");
        assertEquals(33, f.get("books_searchable").getAsInt());
        assertEquals("en", f.getAsJsonArray("languages").get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test
    void newVersionVisibleOnlyAfterRefresh() throws Exception {
        index.publish(Map.of(7, Map.of("zebra", 1)), "t");
        assertEquals(0, ok("/search?q=zebra").get("total").getAsInt()); // aún no refrescado
        reader.refresh();
        assertEquals(1, ok("/search?q=zebra").get("total").getAsInt());
    }
}
