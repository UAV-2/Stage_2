package es.ulpgc.bigdata.searchengine.search;

import es.ulpgc.bigdata.searchengine.common.http.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parámetros validados de GET /search.
 *
 *   q         obligatorio, texto libre (mismas reglas de tokenización que el índice)
 *   author    opcional, coincidencia parcial sin distinguir mayúsculas ("defoe" -> "Daniel Defoe")
 *   language  opcional, código exacto (en, es, fr, de...)
 *   year      opcional, año exacto de 4 cifras
 *   offset    por defecto 0 (>= 0)
 *   limit     por defecto 20, entre 1 y 100
 */
public record SearchQuery(String q, String author, String language, Integer year, int offset, int limit) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,12}");

    public static SearchQuery parse(Map<String, String> p) {
        String q = trim(p.get("q"));
        if (q == null) throw ApiException.badRequest("MISSING_QUERY", "El parámetro 'q' es obligatorio");
        if (q.length() > 500) throw ApiException.badRequest("QUERY_TOO_LONG", "'q' no puede superar 500 caracteres");
        String author = trim(p.get("author"));
        if (author != null && author.length() > 200)
            throw ApiException.badRequest("INVALID_AUTHOR", "'author' no puede superar 200 caracteres");
        String language = trim(p.get("language"));
        if (language != null) {
            language = language.toLowerCase(Locale.ROOT);
            if (!LANGUAGE.matcher(language).matches())
                throw ApiException.badRequest("INVALID_LANGUAGE", "'language' debe ser un código como en, es o fr");
        }
        Integer year = null;
        String y = trim(p.get("year"));
        if (y != null) {
            if (!y.matches("\\d{4}")) throw ApiException.badRequest("INVALID_YEAR", "'year' debe ser un año de 4 cifras");
            year = Integer.valueOf(y);
        }
        int offset = integer(p.get("offset"), 0, "INVALID_OFFSET", "'offset' debe ser un entero >= 0");
        if (offset < 0) throw ApiException.badRequest("INVALID_OFFSET", "'offset' debe ser un entero >= 0");
        int limit = integer(p.get("limit"), DEFAULT_LIMIT, "INVALID_LIMIT", "'limit' debe ser un entero entre 1 y " + MAX_LIMIT);
        if (limit < 1 || limit > MAX_LIMIT)
            throw ApiException.badRequest("INVALID_LIMIT", "'limit' debe ser un entero entre 1 y " + MAX_LIMIT);
        return new SearchQuery(q, author, language, year, offset, limit);
    }

    private static String trim(String s) {
        if (s == null) return null;
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    private static int integer(String raw, int def, String code, String msg) {
        String s = trim(raw);
        if (s == null) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(code, msg);
        }
    }
}
