package es.ulpgc.bigdata.searchengine.search;

import es.ulpgc.bigdata.searchengine.common.BookMetadata;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.http.ApiException;
import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Motor de búsqueda sobre la versión PUBLICADA del índice.
 *
 * - Semántica AND: un libro aparece si contiene todos los términos de la consulta.
 * - Orden: relevancia tf-idf, score = suma de (1 + ln tf) * ln(1 + N / df); empate por book_id.
 * - Filtros (author, language, year) sobre el datamart de metadatos.
 * - total = libros que cumplen consulta + filtros; results = solo la página pedida.
 *
 * Cada consulta trabaja con una snapshot inmutable del manifest, así que es segura con
 * cualquier número de hilos y nunca mezcla dos versiones del índice.
 */
public final class SearchEngine {
    private final IndexReader reader;
    private final MetadataStore metadata;
    private final Tokenizer tokenizer;

    public SearchEngine(IndexReader reader, MetadataStore metadata, Tokenizer tokenizer) {
        this.reader = reader;
        this.metadata = metadata;
        this.tokenizer = tokenizer;
    }

    public Map<String, Object> search(SearchQuery q) {
        IndexReader.Snapshot snap = reader.snapshot();
        List<String> terms = tokenizer.queryTerms(q.q());
        List<Map.Entry<Integer, Double>> scored = new ArrayList<>();

        if (!terms.isEmpty()) {
            List<Map<Integer, Integer>> lists = new ArrayList<>();
            for (String t : terms) lists.add(reader.postings(snap, t));
            lists.sort(Comparator.comparingInt(Map::size));
            int n = Math.max(1, snap.totalBooks());
            Map<Integer, Double> scores = new HashMap<>();
            for (Integer id : lists.get(0).keySet()) {
                double s = 0;
                boolean all = true;
                for (Map<Integer, Integer> l : lists) {
                    Integer tf = l.get(id);
                    if (tf == null) { all = false; break; }
                    s += (1 + Math.log(tf)) * Math.log(1 + (double) n / l.size());
                }
                if (all) scores.put(id, s);
            }
            Map<Integer, BookMetadata> metas = metadata.getMany(scores.keySet());
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                if (matchesFilters(metas.get(e.getKey()), q)) scored.add(e);
            }
            scored.sort((a, b) -> {
                int c = Double.compare(b.getValue(), a.getValue());
                return c != 0 ? c : Integer.compare(a.getKey(), b.getKey());
            });
        }

        List<Integer> page = new ArrayList<>();
        for (int i = q.offset(); i < Math.min(scored.size(), q.offset() + q.limit()); i++) page.add(scored.get(i).getKey());
        Map<Integer, BookMetadata> pageMeta = metadata.getMany(page);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = q.offset(); i < Math.min(scored.size(), q.offset() + q.limit()); i++) {
            Map.Entry<Integer, Double> e = scored.get(i);
            Map<String, Object> r = book(e.getKey(), pageMeta.get(e.getKey()));
            r.put("score", Math.round(e.getValue() * 10000) / 10000.0);
            results.add(r);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        if (q.author() != null) filters.put("author", q.author());
        if (q.language() != null) filters.put("language", q.language());
        if (q.year() != null) filters.put("year", q.year());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q.q());
        out.put("terms", terms);
        out.put("filters", filters);
        out.put("total", scored.size());
        out.put("offset", q.offset());
        out.put("limit", q.limit());
        out.put("index_version", snap.version());
        out.put("results", results);
        return out;
    }

    /** Detalle de un libro ya publicado (los que aún no son buscables devuelven 404). */
    public Map<String, Object> bookDetails(int bookId) {
        IndexReader.Snapshot snap = reader.snapshot();
        BookMetadata m = snap.manifest().books.contains(bookId) ? metadata.get(bookId).orElse(null) : null;
        if (m == null) throw ApiException.notFound("BOOK_NOT_FOUND", "El libro " + bookId + " no está indexado");
        Map<String, Object> out = book(bookId, m);
        out.put("year_source", m.yearSource());
        out.put("release_date", m.releaseDate());
        out.put("index_version", snap.version());
        return out;
    }

    public Map<String, Object> filters() {
        Map<String, Object> out = new LinkedHashMap<>(metadata.filterValues());
        IndexReader.Snapshot snap = reader.snapshot();
        out.put("books_searchable", snap.totalBooks());
        out.put("index_version", snap.version());
        return out;
    }

    public Map<String, Object> stats() {
        IndexReader.Snapshot snap = reader.snapshot();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("index_version", snap.version());
        s.put("books_searchable", snap.totalBooks());
        s.put("segments", snap.segmentDirs().size());
        s.put("postings_cache_entries", reader.cacheSize());
        return s;
    }

    private static boolean matchesFilters(BookMetadata m, SearchQuery q) {
        if (q.author() == null && q.language() == null && q.year() == null) return true;
        if (m == null) return false;
        if (q.author() != null && (m.author() == null
                || !m.author().toLowerCase(Locale.ROOT).contains(q.author().toLowerCase(Locale.ROOT)))) return false;
        if (q.language() != null && !q.language().equals(m.language())) return false;
        return q.year() == null || q.year().equals(m.year());
    }

    private static Map<String, Object> book(int id, BookMetadata m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("book_id", id);
        r.put("title", m == null ? null : m.title());
        r.put("author", m == null ? null : m.author());
        r.put("language", m == null ? null : m.language());
        r.put("year", m == null ? null : m.year());
        return r;
    }
}
