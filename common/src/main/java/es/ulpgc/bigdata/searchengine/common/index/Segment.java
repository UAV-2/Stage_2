package es.ulpgc.bigdata.searchengine.common.index;

import es.ulpgc.bigdata.searchengine.common.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Segmento inmutable del índice invertido, con la estructura de carpetas del Stage 1:
 *
 *   segments/&lt;nombre&gt;/&lt;LETRA&gt;/&lt;término&gt;.txt   una línea "book_id \t tf" por libro, ordenadas por id
 *
 * Se escribe en una carpeta temporal y se renombra al final, así nunca se ve a medias.
 * Como no se modifica después de publicarse, los lectores pueden cachearlo sin riesgo.
 */
public final class Segment {
    private Segment() {}

    /** docs: book_id -> (término -> tf). Devuelve el número de términos escritos. */
    public static int write(Path segmentsDir, String name, Map<Integer, Map<String, Integer>> docs) throws IOException {
        Map<String, List<Posting>> inverted = new HashMap<>();
        docs.forEach((bookId, tfs) -> tfs.forEach((term, tf) ->
                inverted.computeIfAbsent(term, k -> new ArrayList<>()).add(new Posting(bookId, tf))));
        return writeInverted(segmentsDir, name, inverted);
    }

    static int writeInverted(Path segmentsDir, String name, Map<String, List<Posting>> inverted) throws IOException {
        Path tmp = segmentsDir.resolve(name + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Posting>> e : inverted.entrySet()) {
            List<Posting> ps = e.getValue();
            ps.sort(Comparator.comparingInt(Posting::bookId));
            sb.setLength(0);
            for (Posting p : ps) sb.append(p.bookId()).append('\t').append(p.tf()).append('\n');
            Path f = termFile(tmp, e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb, StandardCharsets.UTF_8);
        }
        AtomicFiles.move(tmp, segmentsDir.resolve(name));
        return inverted.size();
    }

    public static List<Posting> read(Path segmentDir, String term) {
        try {
            List<String> lines = Files.readAllLines(termFile(segmentDir, term), StandardCharsets.UTF_8);
            List<Posting> out = new ArrayList<>(lines.size());
            for (String l : lines) {
                int tab = l.indexOf('\t');
                if (tab > 0) out.add(new Posting(Integer.parseInt(l.substring(0, tab)), Integer.parseInt(l.substring(tab + 1))));
            }
            return out;
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Todos los términos de varios segmentos fusionados (para compactar). */
    static Map<String, List<Posting>> readAll(List<Path> segmentDirs) throws IOException {
        Map<String, TreeMap<Integer, Integer>> merged = new HashMap<>();
        for (Path seg : segmentDirs) {
            try (Stream<Path> letters = Files.list(seg)) {
                for (Path letter : letters.toList()) {
                    try (Stream<Path> files = Files.list(letter)) {
                        for (Path f : files.toList()) {
                            String name = f.getFileName().toString();
                            String term = name.substring(0, name.length() - 4);
                            TreeMap<Integer, Integer> m = merged.computeIfAbsent(term, k -> new TreeMap<>());
                            for (Posting p : read(seg, term)) m.put(p.bookId(), p.tf());
                        }
                    }
                }
            }
        }
        Map<String, List<Posting>> out = new HashMap<>();
        merged.forEach((t, m) -> {
            List<Posting> l = new ArrayList<>(m.size());
            m.forEach((id, tf) -> l.add(new Posting(id, tf)));
            out.put(t, l);
        });
        return out;
    }

    static Path termFile(Path segmentDir, String term) {
        return segmentDir.resolve(term.substring(0, 1).toUpperCase(Locale.ROOT)).resolve(term + ".txt");
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    static long sizeOf(Path p) throws IOException {
        if (!Files.exists(p)) return 0;
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }
}
