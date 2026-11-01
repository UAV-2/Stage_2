package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatalakeTest {
    @TempDir
    Path tmp;

    @Test
    void storesWithTimeLayoutAndRecordsLocation() throws Exception {
        Datalake lake = new Datalake(tmp, () -> LocalDateTime.of(2025, 10, 8, 14, 5));
        var r = lake.store(1342, "H", "B");
        assertFalse(r.alreadyPresent());
        assertEquals("datalake/20251008/14/1342", r.location().apiPath());
        assertEquals("B", Files.readString(tmp.resolve("20251008/14/1342.body.txt")));
        assertEquals("1342\t20251008/14\n", Files.readString(tmp.resolve("_locations.tsv")));
        assertEquals(List.of(1342), lake.listBooks());
    }

    @Test
    void storeIsIdempotent() throws Exception {
        Datalake lake = new Datalake(tmp);
        lake.store(5, "H", "B");
        assertTrue(lake.store(5, "H2", "B2").alreadyPresent());
        assertEquals("B", lake.readBody(lake.locate(5).orElseThrow()));
        assertEquals(1, Files.readAllLines(tmp.resolve("_locations.tsv")).size());
    }

    @Test
    void otherInstanceSeesNewBooks() throws Exception {
        Datalake a = new Datalake(tmp);
        Datalake b = new Datalake(tmp); // simula otra réplica (otro proceso)
        assertTrue(b.listBooks().isEmpty());
        a.store(7, "H", "B");
        assertTrue(b.isAvailable(7));
        assertFalse(b.isAvailable(8));
    }

    @Test
    void concurrentWritersDoNotDuplicate() throws Exception {
        Datalake lake = new Datalake(tmp);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Datalake.StoreResult>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int id = i % 10; // cada libro se pide 4 veces a la vez
            fs.add(pool.submit(() -> new Datalake(tmp).store(id, "H" + id, "B" + id)));
        }
        long written = 0;
        for (var f : fs) if (!f.get().alreadyPresent()) written++;
        pool.shutdown();
        assertEquals(10, written);
        assertEquals(10, Files.readAllLines(tmp.resolve("_locations.tsv")).size());
        assertEquals(10, lake.listBooks().size());
    }
}
