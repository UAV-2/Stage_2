package es.ulpgc.bigdata.searchengine.benchmark;

import es.ulpgc.bigdata.searchengine.common.Tokenizer;
import es.ulpgc.bigdata.searchengine.common.index.InvertedIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Coste de publicar un lote de libros ya tokenizados: escribir el segmento (carpetas por
 * letra, un fichero por término) y actualizar el manifest con su lock. Sirve para elegir
 * PUBLISH_BATCH: lotes grandes = menos segmentos y menos commits, pero más espera hasta
 * que los libros son buscables.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IndexPublishBenchmark {

    @Param({"1", "10", "50"})
    public int batchBooks;

    private Map<Integer, Map<String, Integer>> batch;
    private Path dir;
    private InvertedIndex index;
    private int nextId;

    @Setup(Level.Trial)
    public void prepare() {
        Tokenizer tok = new Tokenizer(Set.of("the", "and", "of", "to", "in"));
        List<String> vocab = SyntheticCorpus.vocabulary(50_000, 1);
        batch = new HashMap<>();
        for (int i = 0; i < batchBooks; i++) batch.put(i, tok.termFrequencies(SyntheticCorpus.book(vocab, 20_000, i)));
    }

    @Setup(Level.Iteration)
    public void freshIndex() throws IOException {
        dir = Files.createTempDirectory("jmh-index");
        index = new InvertedIndex(dir, 0);
        nextId = 0;
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Benchmark
    public InvertedIndex.PublishResult publishBatch() throws IOException {
        // ids nuevos en cada invocación para que no se descarten como ya publicados
        Map<Integer, Map<String, Integer>> docs = new HashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : batch.entrySet()) docs.put(nextId++, e.getValue());
        return index.publish(docs, "jmh");
    }
}
