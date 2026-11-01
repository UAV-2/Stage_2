package es.ulpgc.bigdata.searchengine.common;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Configuración por variables de entorno (o propiedades del sistema -Dclave=valor, que
 * tienen prioridad). Así puertos, rutas, réplicas e hilos se cambian sin recompilar.
 */
public final class Config {
    private Config() {}

    public static String str(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public static int integer(String key, int def) {
        String v = str(key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("La variable " + key + " debe ser un entero: " + v);
        }
    }

    public static Path path(String key, String def) {
        return Path.of(str(key, def)).toAbsolutePath().normalize();
    }

    public static List<String> list(String key, String def) {
        return Arrays.stream(str(key, def).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Directorio raíz de datos compartido por todos los servicios (datalake y datamarts). */
    public static Path dataDir() {
        return path("DATA_DIR", "data");
    }

    public static Path stopwordsFile() {
        return path("STOPWORDS", "shared/stopwords.txt");
    }
}
