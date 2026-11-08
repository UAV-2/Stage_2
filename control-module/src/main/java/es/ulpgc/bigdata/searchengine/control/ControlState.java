package es.ulpgc.bigdata.searchengine.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Estado persistente del flujo (control/):
 *
 *   downloaded_books.txt   ids que el Ingestion Service confirmó como descargados
 *   indexed_books.txt      ids PUBLICADOS en el índice (ya buscables)
 *   failed_books.txt       id \t motivo  (fallos permanentes: no existe, sin marcadores)
 *
 * Cada id se añade DESPUÉS de recibir la confirmación del servicio. Si el Control Module se
 * cae entre la confirmación y la escritura, al reanudar se repite la operación, que es
 * idempotente en los servicios. Solo hay UN proceso de control, así que basta con
 * sincronizar los hilos de este proceso.
 */
public final class ControlState {
    private final Path downloadedFile;
    private final Path indexedFile;
    private final Path failedFile;
    private final Set<Integer> downloaded = new HashSet<>();
    private final Set<Integer> indexed = new HashSet<>();
    private final Map<Integer, String> failed = new HashMap<>();

    public ControlState(Path dir) throws IOException {
        Files.createDirectories(dir);
        downloadedFile = dir.resolve("downloaded_books.txt");
        indexedFile = dir.resolve("indexed_books.txt");
        failedFile = dir.resolve("failed_books.txt");
        for (String l : read(downloadedFile)) downloaded.add(Integer.parseInt(l));
        for (String l : read(indexedFile)) indexed.add(Integer.parseInt(l));
        for (String l : read(failedFile)) {
            String[] p = l.split("\t", 2);
            failed.put(Integer.parseInt(p[0]), p.length > 1 ? p[1] : "");
        }
    }

    public synchronized boolean isDownloaded(int id) { return downloaded.contains(id); }

    public synchronized boolean isIndexed(int id) { return indexed.contains(id); }

    public synchronized boolean isFailed(int id) { return failed.containsKey(id); }

    public synchronized Set<Integer> downloadedNotIndexed() {
        Set<Integer> s = new HashSet<>(downloaded);
        s.removeAll(indexed);
        return s;
    }

    public synchronized void markDownloaded(int id) throws IOException {
        if (downloaded.add(id)) append(downloadedFile, Integer.toString(id));
    }

    public synchronized void markIndexed(int id) throws IOException {
        if (indexed.add(id)) append(indexedFile, Integer.toString(id));
    }

    public synchronized void markFailed(int id, String reason) throws IOException {
        if (failed.putIfAbsent(id, reason) == null) append(failedFile, id + "\t" + reason.replace('\n', ' '));
    }

    public synchronized Map<String, Integer> counts() {
        return Map.of("downloaded", downloaded.size(), "indexed", indexed.size(), "failed", failed.size());
    }

    private static void append(Path f, String line) throws IOException {
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }

    private static java.util.List<String> read(Path f) throws IOException {
        if (!Files.exists(f)) return java.util.List.of();
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
