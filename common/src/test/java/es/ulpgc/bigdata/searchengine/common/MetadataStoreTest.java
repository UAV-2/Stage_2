package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataStoreTest {
    @TempDir
    Path tmp;

    private static BookMetadata book(int id, String author, String lang, Integer year) {
        return new BookMetadata(id, "Title " + id, author, lang, year, year == null ? null : "gutenberg_release",
                null, "datalake/x/" + id);
    }

    @Test
    void upsertIsIdempotentAndReadsBack() {
        try (MetadataStore m = new MetadataStore(tmp.resolve("datamarts/metadata.db"))) {
            m.upsertAll(List.of(book(5, "Daniel Defoe", "en", 1719), book(1342, "Jane Austen", "en", 1813)));
            m.upsertAll(List.of(book(5, "Daniel Defoe", "en", 1719)));
            assertEquals(2, m.count());
            assertEquals("Jane Austen", m.get(1342).orElseThrow().author());
            assertTrue(m.get(9).isEmpty());
            assertEquals(2, m.getMany(List.of(5, 1342, 9)).size());
        }
    }

    @Test
    void nullYearAndFilterValues() {
        try (MetadataStore m = new MetadataStore(tmp.resolve("m.db"))) {
            m.upsertAll(List.of(book(1, "A", "en", null), book(2, "A", "fr", 1900), book(3, "B", "en", 1950)));
            assertNull(m.get(1).orElseThrow().year());
            Map<String, Object> f = m.filterValues();
            assertEquals(List.of(Map.of("code", "en", "books", 2), Map.of("code", "fr", "books", 1)), f.get("languages"));
            assertEquals(1900, ((Map<?, ?>) f.get("years")).get("min"));
        }
    }

    @Test
    void worksFromSeveralThreadsAndInstances() throws Exception {
        Path db = tmp.resolve("m.db");
        new MetadataStore(db).close(); // la BD ya existe; aquí se prueban escrituras concurrentes
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            int base = t * 100;
            ts[t] = new Thread(() -> {
                try (MetadataStore m = new MetadataStore(db)) { // una instancia por "réplica"
                    for (int i = 0; i < 20; i++) m.upsertAll(List.of(book(base + i, "X", "en", 2000)));
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        try (MetadataStore m = new MetadataStore(db)) {
            assertEquals(80, m.count());
        }
    }
}
