package es.ulpgc.bigdata.searchengine.common.index;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import es.ulpgc.bigdata.searchengine.common.AtomicFiles;
import es.ulpgc.bigdata.searchengine.common.FileLocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Índice invertido por segmentos inmutables + manifest publicado (datamarts/index/).
 *
 *   index/segments/seg-.../      segmentos (estructura de carpetas del Stage 1)
 *   index/manifest.json          versión publicada: segmentos visibles y libros incluidos
 *   index/.manifest.lock         lock entre réplicas del indexador
 *
 * Escribir un segmento no necesita lock (cada escritor usa un nombre único). Solo la
 * actualización del manifest (leer-modificar-escribir) se hace en exclusión mutua, y el
 * manifest se sustituye de forma atómica: la búsqueda ve la versión anterior completa o la
 * nueva completa, nunca un estado intermedio.
 */
public final class InvertedIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicLong SEQ = new AtomicLong();

    private final Path dir;
    private final Path segmentsDir;
    private final Path manifestFile;
    private final Path lockFile;
    private final long retiredGraceMillis;

    public record PublishResult(long version, List<Integer> published, List<Integer> skipped, String segment) {}

    public record CompactResult(long version, int mergedSegments, String segment, int terms) {}

    public InvertedIndex(Path dir) {
        this(dir, 60_000);
    }

    public InvertedIndex(Path dir, long retiredGraceMillis) {
        this.dir = dir;
        this.segmentsDir = dir.resolve("segments");
        this.manifestFile = dir.resolve("manifest.json");
        this.lockFile = dir.resolve(".manifest.lock");
        this.retiredGraceMillis = retiredGraceMillis;
    }

    public Path dir() { return dir; }

    public Path segmentDir(String name) { return segmentsDir.resolve(name); }

    public Manifest readManifest() throws IOException {
        if (!Files.exists(manifestFile)) return new Manifest();
        Manifest m = GSON.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8), Manifest.class);
        return m == null ? new Manifest() : m;
    }

    /**
     * Publica un lote de libros: escribe un segmento nuevo y lo añade al manifest.
     * Idempotente: los libros que ya estaban publicados se ignoran (aparecen en skipped).
     */
    public PublishResult publish(Map<Integer, Map<String, Integer>> docs, String writerId) throws IOException {
        Manifest snapshot = readManifest();
        Map<Integer, Map<String, Integer>> fresh = new HashMap<>(docs);
        List<Integer> skipped = new ArrayList<>();
        for (Integer id : docs.keySet()) {
            if (snapshot.books.contains(id)) {
                fresh.remove(id);
                skipped.add(id);
            }
        }
        if (fresh.isEmpty()) return new PublishResult(snapshot.version, List.of(), sorted(skipped), null);

        String name = newSegmentName(writerId);
        Segment.write(segmentsDir, name, fresh); // fuera del lock: no bloquea a otras réplicas
        return FileLocks.withLock(lockFile, () -> {
            Manifest m = readManifest();
            Map<Integer, Map<String, Integer>> toPublish = new HashMap<>(fresh);
            String segment = name;
            boolean overlap = toPublish.keySet().removeIf(m.books::contains);
            if (overlap) { // otra réplica publicó alguno de estos libros mientras escribíamos (raro)
                for (Integer id : fresh.keySet()) if (!toPublish.containsKey(id)) skipped.add(id);
                Segment.deleteTree(segmentsDir.resolve(name));
                if (toPublish.isEmpty()) return new PublishResult(m.version, List.of(), sorted(skipped), null);
                segment = newSegmentName(writerId);
                Segment.write(segmentsDir, segment, toPublish);
            }
            m.segments.add(segment);
            m.books.addAll(toPublish.keySet());
            commit(m);
            return new PublishResult(m.version, sorted(new ArrayList<>(toPublish.keySet())), sorted(skipped), segment);
        });
    }

    /** Fusiona todos los segmentos publicados en uno (menos ficheros que abrir en cada consulta). */
    public CompactResult compact(String writerId) throws IOException {
        Manifest snapshot = readManifest();
        if (snapshot.segments.size() < 2) return null;
        List<String> merging = new ArrayList<>(snapshot.segments);
        List<Path> dirs = merging.stream().map(segmentsDir::resolve).toList();
        Map<String, List<Posting>> inverted = Segment.readAll(dirs);
        String name = newSegmentName(writerId + "-merged");
        int terms = Segment.writeInverted(segmentsDir, name, inverted);
        return FileLocks.withLock(lockFile, () -> {
            Manifest m = readManifest();
            if (!m.segments.containsAll(merging)) { // alguien hizo un rebuild o compactó antes
                Segment.deleteTree(segmentsDir.resolve(name));
                return null;
            }
            List<String> segments = new ArrayList<>();
            segments.add(name);
            for (String s : m.segments) if (!merging.contains(s)) segments.add(s);
            long now = System.currentTimeMillis();
            for (String s : merging) m.retired.add(new Manifest.Retired(s, now));
            m.segments = segments;
            commit(m);
            return new CompactResult(m.version, merging.size(), name, terms);
        });
    }

    /** Sustituye todo el índice por uno construido desde cero con los libros indicados. */
    public PublishResult rebuild(Map<Integer, Map<String, Integer>> all, String writerId) throws IOException {
        String name = newSegmentName(writerId + "-rebuild");
        Segment.write(segmentsDir, name, all);
        return FileLocks.withLock(lockFile, () -> {
            Manifest m = readManifest();
            long now = System.currentTimeMillis();
            for (String s : m.segments) m.retired.add(new Manifest.Retired(s, now));
            m.segments = new ArrayList<>(List.of(name));
            m.books.clear();
            m.books.addAll(all.keySet());
            commit(m);
            return new PublishResult(m.version, sorted(new ArrayList<>(all.keySet())), List.of(), name);
        });
    }

    public long sizeBytes() throws IOException {
        return Segment.sizeOf(segmentsDir);
    }

    /** Llamar con el lock tomado. */
    private void commit(Manifest m) throws IOException {
        m.version++;
        m.updatedAt = Instant.now().toString();
        long now = System.currentTimeMillis();
        List<Manifest.Retired> keep = new ArrayList<>();
        for (Manifest.Retired r : m.retired) {
            if (now - r.retiredAtMillis >= retiredGraceMillis) Segment.deleteTree(segmentsDir.resolve(r.segment));
            else keep.add(r);
        }
        m.retired = keep;
        AtomicFiles.write(manifestFile, GSON.toJson(m));
    }

    private static String newSegmentName(String writerId) {
        String safe = writerId.replaceAll("[^A-Za-z0-9_-]", "_");
        return "seg-" + System.currentTimeMillis() + "-" + safe + "-" + SEQ.incrementAndGet()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<Integer> sorted(List<Integer> l) {
        l.sort(null);
        return l;
    }
}
