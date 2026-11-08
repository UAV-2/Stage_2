package es.ulpgc.bigdata.searchengine.benchmark;

import es.ulpgc.bigdata.searchengine.common.BookMetadata;
import es.ulpgc.bigdata.searchengine.common.MetadataStore;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.index.IndexReader;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import es.ulpgc.bigdata.searchengine.search.SearchEngine;
import es.ulpgc.bigdata.searchengine.search.SearchQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Consulta completa del Search Service sin HTTP: postings de cada término (con caché),
 * intersección AND, scoring tf-idf, filtros sobre SQLite y paginación.
 * Throughput en consultas/s; con -t 1,2,4,8 se ve cómo escala con hilos dentro de un proceso.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SearchBenchmark {

    @Param({"100", "500"})
    public int books;

    /** Número de segmentos en que se publica el índice (1 = recién compactado). */
    @Param({"1", "10"})
    public int segments;

    private SearchEngine engine;
    private MetadataStore metadata;
    private Path dir;
    private final List<SearchQuery> queries = new ArrayList<>();

    @Setup
    public void setup() throws IOException {
        dir = Files.createTempDirectory("jmh-search");
        Tokenizer tok = new Tokenizer(Set.of("the", "and", "of", "to", "in", "that", "it", "was", "he", "his"));
        List<String> vocab = SyntheticCorpus.vocabulary(50_000, 1);
        InvertedIndex index = new InvertedIndex(dir.resolve("index"));
        metadata = new MetadataStore(dir.resolve("metadata.db"));
        List<BookMetadata> metas = new ArrayList<>();
        int perSegment = Math.max(1, books / segments);
        Map<Integer, Map<String, Integer>> batch = new HashMap<>();
        for (int id = 1; id <= books; id++) {
            batch.put(id, tok.termFrequencies(SyntheticCorpus.book(vocab, 10_000, id)));
            metas.add(es.ulpgc.bigdata.searchengine.common.HeaderParser.parse(id, SyntheticCorpus.header(id, id)));
            if (batch.size() == perSegment || id == books) {
                index.publish(batch, "jmh");
                batch = new HashMap<>();
            }
        }
        metadata.upsertAll(metas);
        IndexReader reader = new IndexReader(index, 50_000);
        reader.refresh();
        engine = new SearchEngine(reader, metadata, tok);
        String[][] qs = {{"adventure", null, null}, {"island sea", null, null}, {"captain ship", "en", null},
                {"love", null, "austen"}, {"war peace", "en", "defoe"}, {"kalo", null, null}};
        for (String[] q : qs) queries.add(new SearchQuery(q[0], q[2], q[1], null, 0, 20));
    }

    @TearDown
    public void close() {
        metadata.close();
    }

    @Benchmark
    public int searchWorkload() {
        int total = 0;
        for (SearchQuery q : queries) total += (Integer) engine.search(q).get("total");
        return total;
    }
}
