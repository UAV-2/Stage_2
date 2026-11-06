package es.ulpgc.bigdata.searchengine.common.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP mínimo para las llamadas internas entre servicios (y para los tests).
 * Lanza IOException si el servicio no responde (caído, timeout), lo que el llamador
 * trata como fallo transitorio y reintenta.
 */
public final class ServiceClient {
    private final HttpClient client;
    private final Duration timeout;

    public record Response(int status, JsonObject body, String raw) {
        public boolean ok() { return status >= 200 && status < 300; }

        public String str(String field) {
            JsonElement e = body == null ? null : body.get(field);
            return e == null || e.isJsonNull() ? null : e.getAsString();
        }
    }

    public ServiceClient(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Response get(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    public Response post(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private Response send(HttpRequest.Builder b) throws IOException {
        try {
            HttpResponse<String> r = client.send(b.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
            JsonObject body = null;
            try {
                JsonElement e = JsonParser.parseString(r.body());
                if (e.isJsonObject()) body = e.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // respuesta que no es JSON: se devuelve en raw
            }
            return new Response(r.statusCode(), body, r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido", e);
        }
    }
}
