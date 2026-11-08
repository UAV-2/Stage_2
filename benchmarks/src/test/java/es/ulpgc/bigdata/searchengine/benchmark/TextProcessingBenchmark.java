package es.ulpgc.bigdata.searchengine.benchmark;

import es.ulpgc.bigdata.searchengine.common.BookMetadata;
import es.ulpgc.bigdata.searchengine.common.GutenbergText;
import es.ulpgc.bigdata.searchengine.common.HeaderParser;
import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import java.nio.charset.StandardCharsets;
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

/**
 * Trabajo de CPU por libro que hacen Ingestion (separar header/body) e Indexing
 * (tokenizar y contar frecuencias, parsear metadatos). Es seguro con varios hilos: para el
 * experimento de hilos se ejecuta con -t 1,2,4,8 (ver scripts/run_jmh.sh).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TextProcessingBenchmark {

    /** Palabras por libro (un libro real de Gutenberg tiene ~50.000-100.000). */
    @Param({"10000", "50000", "100000"})
    public int words;

    private Tokenizer tokenizer;
    private String body;
    private String header;
    private byte[] raw;

    @Setup
    public void setup() {
        tokenizer = new Tokenizer(Set.of("the", "and", "of", "to", "in", "that", "it", "was", "he", "his"));
        body = SyntheticCorpus.book(SyntheticCorpus.vocabulary(50_000, 1), words, 42);
        header = SyntheticCorpus.header(1342, 42);
        raw = (header + "\n*** START OF THE PROJECT GUTENBERG EBOOK BOOK ***\n" + body
                + "\n*** END OF THE PROJECT GUTENBERG EBOOK BOOK ***\nfooter").getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public GutenbergText.Parts splitHeaderBody() {
        return GutenbergText.split(raw).orElseThrow();
    }

    @Benchmark
    public Map<String, Integer> tokenizeAndCount() {
        return tokenizer.termFrequencies(body);
    }

    @Benchmark
    public BookMetadata parseHeader() {
        return HeaderParser.parse(1342, header);
    }
}
