package es.ulpgc.bigdata.searchengine.common.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lectura del índice publicado. Cada consulta usa una {@link Snapshot} (versión del
 * manifest) inmutable, así que todos los términos de una consulta se leen de la misma
 * versión aunque el indexador publique otra mientras tanto. Como los segmentos son
 * inmutables, sus postings se pueden cachear sin invalidación (LRU compartida entre hilos).
 */
public final class IndexReader {
    private final InvertedIndex index;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Map<String, List<Posting>> cache;

    public record Snapshot(Manifest manifest, List<Path> segmentDirs) {
        public long version() { return manifest.version; }
        public int totalBooks() { return manifest.books.size(); }
    }

    public IndexReader(InvertedIndex index, int cacheEntries) {
        this.index = index;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Posting>> e) {
                return size() > cacheEntries;
            }
        });
        refresh();
    }

    /** Relee el manifest y, si hay una versión nueva, la hace visible para las consultas siguientes. */
    public Snapshot refresh() {
        try {
            Manifest m = index.readManifest();
            Snapshot old = current.get();
            if (old == null || old.version() != m.version) {
                List<Path> dirs = m.segments.stream().map(index::segmentDir).toList();
                current.set(new Snapshot(m, dirs));
            }
            return current.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Snapshot snapshot() {
        return current.get();
    }

    /** book_id -> tf del término en la versión publicada de la snapshot. */
    public Map<Integer, Integer> postings(Snapshot s, String term) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Path seg : s.segmentDirs()) {
            String key = seg.getFileName() + "/" + term;
            List<Posting> ps = cache.get(key);
            if (ps == null) {
                ps = Segment.read(seg, term);
                cache.put(key, ps);
            }
            for (Posting p : ps) out.put(p.bookId(), p.tf());
        }
        return out;
    }

    public int cacheSize() {
        return cache.size();
    }
}
