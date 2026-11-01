package es.ulpgc.bigdata.searchengine.common.index;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Estado PUBLICADO del índice (datamarts/index/manifest.json). La búsqueda solo lee los
 * segmentos que aparecen aquí; publicar = sustituir este fichero de forma atómica.
 */
public final class Manifest {
    public long version = 0;
    public String updatedAt = null;
    public List<String> segments = new ArrayList<>();
    public TreeSet<Integer> books = new TreeSet<>();
    /** Segmentos sustituidos por una compactación o un rebuild; se borran tras un tiempo de gracia. */
    public List<Retired> retired = new ArrayList<>();

    public static final class Retired {
        public String segment;
        public long retiredAtMillis;

        public Retired(String segment, long retiredAtMillis) {
            this.segment = segment;
            this.retiredAtMillis = retiredAtMillis;
        }
    }

    public Manifest copy() {
        Manifest m = new Manifest();
        m.version = version;
        m.updatedAt = updatedAt;
        m.segments = new ArrayList<>(segments);
        m.books = new TreeSet<>(books);
        m.retired = new ArrayList<>(retired);
        return m;
    }
}
