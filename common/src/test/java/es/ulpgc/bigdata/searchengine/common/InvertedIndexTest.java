package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvertedIndexTest {
    @TempDir
    Path tmp;

    private static Map<Integer, Map<String, Integer>> docs(Object... kv) {
        Map<Integer, Map<String, Integer>> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], Map.of((String) kv[i + 1], 1));
        return m;
    }

    @Test
    void publishMakesBooksVisibleOnlyAfterManifestSwap() throws Exception {
        InvertedIndex idx = new InvertedIndex(tmp);
        IndexReader reader = new IndexReader(idx, 1000);
        assertEquals(0, reader.snapshot().version());
        var r = idx.publish(Map.of(5, Map.of("island", 3, "sea", 1), 12, Map.of("island", 1)), "t");
        assertEquals(1, r.version());
        assertEquals(List.of(5, 12), r.published());
        // la snapshot antigua sigue sin ver nada hasta refrescar
        assertTrue(reader.postings(reader.snapshot(), "island").isEmpty());
        var s = reader.refresh();
        assertEquals(Map.of(5, 3, 12, 1), reader.postings(s, "island"));
        assertEquals("5\t3\n12\t1\n", Files.readString(idx.segmentDir(r.segment()).resolve("I/island.txt")));
    }

    @Test
    void publishIsIdempotent() throws Exception {
        InvertedIndex idx = new InvertedIndex(tmp);
        idx.publish(docs(1, "a"), "t");
        var r = idx.publish(docs(1, "a", 2, "b"), "t");
        assertEquals(List.of(2), r.published());
        assertEquals(List.of(1), r.skipped());
        var again = idx.publish(docs(1, "a"), "t");
        assertNull(again.segment());
        assertEquals(2, again.version());
    }

    @Test
    void compactMergesAndKeepsContent() throws Exception {
        InvertedIndex idx = new InvertedIndex(tmp, 0);
        for (int i = 1; i <= 5; i++) idx.publish(docs(i, "word"), "t");
        IndexReader reader = new IndexReader(idx, 10);
        var before = reader.postings(reader.refresh(), "word");
        var c = idx.compact("t");
        assertEquals(5, c.mergedSegments());
        var s = reader.refresh();
        assertEquals(1, s.segmentDirs().size());
        assertEquals(before, reader.postings(s, "word"));
        idx.publish(docs(9, "z"), "t"); // el siguiente commit borra los segmentos retirados (gracia 0)
        try (var st = Files.list(tmp.resolve("segments"))) {
            assertEquals(2, st.count());
        }
    }

    @Test
    void rebuildReplacesEverything() throws Exception {
        InvertedIndex idx = new InvertedIndex(tmp);
        idx.publish(docs(1, "old"), "t");
        idx.rebuild(docs(2, "new"), "t");
        var m = idx.readManifest();
        assertEquals(List.of(2), new ArrayList<>(m.books));
        assertEquals(1, m.segments.size());
        assertEquals(1, m.retired.size());
    }

    @Test
    void concurrentReplicasPublishWithoutLosingBooks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            int writer = w;
            fs.add(pool.submit(() -> {
                InvertedIndex idx = new InvertedIndex(tmp); // una instancia por "réplica"
                for (int i = 0; i < 10; i++) {
                    int id = writer * 100 + i;
                    idx.publish(docs(id, "common", 999, "dup"), "w" + writer); // 999 lo publican todos
                }
                return null;
            }));
        }
        for (var f : fs) f.get();
        pool.shutdown();
        InvertedIndex idx = new InvertedIndex(tmp);
        var m = idx.readManifest();
        assertEquals(61, m.books.size());
        IndexReader reader = new IndexReader(idx, 100);
        assertEquals(60, reader.postings(reader.refresh(), "common").size());
        assertEquals(1, reader.postings(reader.snapshot(), "dup").size());
    }
}
