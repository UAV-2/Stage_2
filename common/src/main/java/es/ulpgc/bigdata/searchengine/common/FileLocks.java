package es.ulpgc.bigdata.searchengine.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusión mutua entre hilos Y entre procesos (réplicas) de la misma máquina.
 *
 * Un {@link ReentrantLock} solo protege a los hilos de un proceso; un {@link FileLock} del
 * sistema operativo protege entre procesos pero no entre hilos de la misma JVM. Aquí se usan
 * los dos: primero el lock local y después el del fichero. En el Stage 3 (varias máquinas)
 * esto debe sustituirse por un mecanismo distribuido (p. ej. un coordinador o la base de datos).
 *
 * No se deben anidar dos llamadas sobre el mismo fichero en el mismo hilo.
 */
public final class FileLocks {
    private FileLocks() {}

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface IoAction<T> {
        T run() throws IOException;
    }

    public static <T> T withLock(Path lockFile, IoAction<T> action) throws IOException {
        Path key = lockFile.toAbsolutePath().normalize();
        ReentrantLock local = LOCAL.computeIfAbsent(key, k -> new ReentrantLock());
        local.lock();
        try {
            Files.createDirectories(key.getParent());
            try (FileChannel ch = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                return action.run();
            }
        } finally {
            local.unlock();
        }
    }
}
