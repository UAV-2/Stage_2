package es.ulpgc.bigdata.searchengine.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Origen de los libros. Por defecto Project Gutenberg; para pruebas sin red se puede usar
 * una carpeta local con SOURCE=local:/ruta (ficheros &lt;id&gt;.txt o pg&lt;id&gt;.txt).
 */
public interface BookSource {
    /** Bytes del libro, o null si no existe. IOException = fallo de red (se puede reintentar). */
    byte[] fetch(int bookId) throws IOException;

    String describe();

    static BookSource fromSpec(String spec, long minDelayMillis) {
        if (spec.startsWith("local:")) return new LocalSource(Path.of(spec.substring(6)));
        if ("gutenberg".equals(spec)) spec = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt";
        if (!spec.contains("{id}")) throw new IllegalArgumentException("SOURCE debe ser 'gutenberg', 'local:<dir>' o una URL con {id}");
        return new HttpSource(spec, minDelayMillis);
    }

    final class HttpSource implements BookSource {
        private final String template;
        private final long minDelayMillis;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        private long lastRequest = 0;

        HttpSource(String template, long minDelayMillis) {
            this.template = template;
            this.minDelayMillis = minDelayMillis;
        }

        @Override
        public byte[] fetch(int bookId) throws IOException {
            politeWait();
            String url = template.replace("{id}", Integer.toString(bookId));
            try {
                HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "ulpgc-bigdata-stage2/1.0 (proyecto academico)")
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 404) return null;
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " en " + url);
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrumpido", e);
            }
        }

        /** No más de una petición cada minDelayMillis por proceso, para no saturar Gutenberg. */
        private synchronized void politeWait() {
            long wait = minDelayMillis - (System.currentTimeMillis() - lastRequest);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            lastRequest = System.currentTimeMillis();
        }

        @Override
        public String describe() { return template + " (espera mínima " + minDelayMillis + " ms)"; }
    }

    final class LocalSource implements BookSource {
        private final Path dir;

        LocalSource(Path dir) { this.dir = dir; }

        @Override
        public byte[] fetch(int bookId) throws IOException {
            for (String name : new String[]{bookId + ".txt", "pg" + bookId + ".txt"}) {
                Path p = dir.resolve(name);
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
            return null;
        }

        @Override
        public String describe() { return "local:" + dir; }
    }
}
