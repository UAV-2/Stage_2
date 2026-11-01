package es.ulpgc.bigdata.searchengine.common;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Datalake con la estructura elegida en el Stage 1 (por tiempo de descarga):
 *
 *   datalake/YYYYMMDD/HH/&lt;id&gt;.header.txt
 *   datalake/YYYYMMDD/HH/&lt;id&gt;.body.txt
 *   datalake/_locations.tsv      id \t YYYYMMDD/HH   (registro persistente de libros completos)
 *
 * Un libro está disponible SOLO cuando su línea aparece en _locations.tsv, que se escribe
 * después de que el header y el body se hayan escrito de forma atómica. Varias réplicas del
 * servicio de ingesta pueden escribir a la vez: cada libro se protege con un lock por libro
 * (repartido en 64 franjas) y el fichero de ubicaciones con su propio lock.
 */
public final class Datalake {
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd/HH");
    private static final int STRIPES = 64;

    private final Path root;
    private final Path locationsFile;
    private final Supplier<LocalDateTime> clock;
    private final Map<Integer, String> locations = new HashMap<>();
    private long readOffset = 0;

    public record Location(int bookId, String folder, Path root) {
        public Path dir() { return root.resolve(folder); }
        public Path headerPath() { return dir().resolve(bookId + ".header.txt"); }
        public Path bodyPath() { return dir().resolve(bookId + ".body.txt"); }
        /** Ruta lógica que se devuelve en la API, p. ej. datalake/20251008/14/1342 */
        public String apiPath() { return "datalake/" + folder + "/" + bookId; }
    }

    public record StoreResult(Location location, boolean alreadyPresent) {}

    public Datalake(Path root) {
        this(root, LocalDateTime::now);
    }

    public Datalake(Path root, Supplier<LocalDateTime> clock) {
        this.root = root;
        this.locationsFile = root.resolve("_locations.tsv");
        this.clock = clock;
    }

    public Path root() { return root; }

    /** Guarda un libro. Idempotente: si ya estaba disponible no se vuelve a escribir. */
    public StoreResult store(int bookId, String header, String body) throws IOException {
        return FileLocks.withLock(root.resolve(".locks/book-" + Math.floorMod(bookId, STRIPES) + ".lock"), () -> {
            Optional<Location> existing = locate(bookId);
            if (existing.isPresent()) return new StoreResult(existing.get(), true);
            Location loc = new Location(bookId, clock.get().format(FOLDER), root);
            AtomicFiles.write(loc.bodyPath(), body);
            AtomicFiles.write(loc.headerPath(), header);
            FileLocks.withLock(root.resolve(".locks/locations.lock"), () -> {
                Files.writeString(locationsFile, bookId + "\t" + loc.folder() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                return null;
            });
            synchronized (this) {
                locations.put(bookId, loc.folder());
            }
            return new StoreResult(loc, false);
        });
    }

    public Optional<Location> locate(int bookId) throws IOException {
        String folder;
        synchronized (this) {
            refresh();
            folder = locations.get(bookId);
        }
        if (folder == null) return Optional.empty();
        Location loc = new Location(bookId, folder, root);
        return Files.exists(loc.headerPath()) && Files.exists(loc.bodyPath()) ? Optional.of(loc) : Optional.empty();
    }

    public boolean isAvailable(int bookId) throws IOException {
        return locate(bookId).isPresent();
    }

    /** Ids disponibles, ordenados. */
    public List<Integer> listBooks() throws IOException {
        synchronized (this) {
            refresh();
            List<Integer> ids = new ArrayList<>(locations.keySet());
            ids.sort(null);
            return ids;
        }
    }

    public String readHeader(Location loc) throws IOException {
        return Files.readString(loc.headerPath(), StandardCharsets.UTF_8);
    }

    public String readBody(Location loc) throws IOException {
        return Files.readString(loc.bodyPath(), StandardCharsets.UTF_8);
    }

    /** Lee solo lo añadido a _locations.tsv desde la última lectura (otras réplicas pueden escribir). */
    private void refresh() throws IOException {
        if (!Files.exists(locationsFile)) return;
        long size = Files.size(locationsFile);
        if (size <= readOffset) return;
        try (RandomAccessFile raf = new RandomAccessFile(locationsFile.toFile(), "r")) {
            raf.seek(readOffset);
            byte[] buf = new byte[(int) (size - readOffset)];
            raf.readFully(buf);
            String chunk = new String(buf, StandardCharsets.UTF_8);
            int lastNl = chunk.lastIndexOf('\n');
            if (lastNl < 0) return; // línea a medio escribir: se leerá en la próxima llamada
            for (String line : chunk.substring(0, lastNl).split("\n")) {
                String[] p = line.split("\t");
                if (p.length == 2) locations.put(Integer.parseInt(p[0].strip()), p[1].strip());
            }
            readOffset += chunk.substring(0, lastNl + 1).getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
