package es.ulpgc.bigdata.searchengine.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Escritura atómica: se escribe en un temporal del mismo directorio y se renombra. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, content);
        move(tmp, target);
    }

    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
