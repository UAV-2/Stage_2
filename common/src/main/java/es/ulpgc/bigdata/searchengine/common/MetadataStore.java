package es.ulpgc.bigdata.searchengine.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Datamart de metadatos en SQLite (datamarts/metadata.db).
 *
 * Concurrencia: modo WAL (un escritor y muchos lectores a la vez, también entre procesos) y
 * busy_timeout para que los escritores de distintas réplicas esperen en lugar de fallar.
 * Las conexiones JDBC de SQLite no son seguras entre hilos, así que cada hilo usa la suya.
 */
public final class MetadataStore implements AutoCloseable {
    private static final String COLUMNS =
            "book_id, title, author, language, year, year_source, release_date, body_path";

    private final String url;
    private final ThreadLocal<Connection> connection;
    private final ConcurrentLinkedQueue<Connection> opened = new ConcurrentLinkedQueue<>();

    public MetadataStore(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        this.connection = ThreadLocal.withInitial(this::open);
        createSchema();
    }

    /**
     * Crea la tabla y los índices si no existen. Si varias réplicas crean la BD a la vez, SQLite
     * puede dar errores pasajeros (BUSY, o READONLY mientras otra conexión prepara el WAL): se
     * reintenta con una conexión nueva.
     */
    private void createSchema() {
        SQLException last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            Connection c = conn();
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS books (book_id INTEGER PRIMARY KEY, title TEXT, author TEXT, "
                        + "language TEXT, year INTEGER, year_source TEXT, release_date TEXT, body_path TEXT, "
                        + "indexed_at TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_books_author ON books(author COLLATE NOCASE)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_books_year ON books(year)");
                return;
            } catch (SQLException e) {
                last = e;
                connection.remove(); // el siguiente intento abre otra conexión
                opened.remove(c);
                try { c.close(); } catch (SQLException ignored) { }
                if (!pause(attempt)) break;
            }
        }
        throw new IllegalStateException("No se pudo crear el esquema en " + url, last);
    }

    /** Espera antes de reintentar (50 ms, 100 ms, 150 ms...); false si interrumpen el hilo. */
    private static boolean pause(int attempt) {
        try {
            Thread.sleep(50L * attempt);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Connection open() {
        SQLException last = null;
        for (int attempt = 1; attempt <= 10; attempt++) { // varias réplicas pueden abrir la BD a la vez
            try {
                Connection c = DriverManager.getConnection(url);
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout=10000");
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                } catch (SQLException e) {
                    c.close();
                    throw e;
                }
                opened.add(c);
                return c;
            } catch (SQLException e) {
                last = e;
                if (!pause(attempt)) break;
            }
        }
        throw new IllegalStateException("No se pudo abrir " + url, last);
    }

    private Connection conn() {
        return connection.get();
    }

    /** Inserta o reemplaza (idempotente: reindexar un libro no crea duplicados). */
    public void upsertAll(Collection<BookMetadata> books) {
        if (books.isEmpty()) return;
        Connection c = conn();
        String now = Instant.now().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO books (" + COLUMNS + ", indexed_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            c.setAutoCommit(false);
            for (BookMetadata b : books) {
                ps.setInt(1, b.bookId());
                ps.setString(2, b.title());
                ps.setString(3, b.author());
                ps.setString(4, b.language());
                if (b.year() == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, b.year());
                ps.setString(6, b.yearSource());
                ps.setString(7, b.releaseDate());
                ps.setString(8, b.bodyPath());
                ps.setString(9, now);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            rollback(c);
            throw new IllegalStateException(e);
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public Optional<BookMetadata> get(int bookId) {
        Map<Integer, BookMetadata> m = getMany(List.of(bookId));
        return Optional.ofNullable(m.get(bookId));
    }

    /** Metadatos de varios libros (consultas por lotes de 500 ids). */
    public Map<Integer, BookMetadata> getMany(Collection<Integer> ids) {
        Map<Integer, BookMetadata> out = new HashMap<>();
        List<Integer> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += 500) {
            List<Integer> chunk = list.subList(i, Math.min(list.size(), i + 500));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT " + COLUMNS + " FROM books WHERE book_id IN (" + marks + ")")) {
                for (int k = 0; k < chunk.size(); k++) ps.setInt(k + 1, chunk.get(k));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BookMetadata b = row(rs);
                        out.put(b.bookId(), b);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public int count() {
        try (Statement s = conn().createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM books")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Valores disponibles para los filtros: idiomas con su número de libros y rango de años. */
    public Map<String, Object> filterValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> langs = new ArrayList<>();
        try (Statement s = conn().createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT language, COUNT(*) FROM books WHERE language IS NOT NULL "
                    + "GROUP BY language ORDER BY COUNT(*) DESC, language")) {
                while (rs.next()) langs.add(Map.of("code", rs.getString(1), "books", rs.getInt(2)));
            }
            Map<String, Object> years = new LinkedHashMap<>();
            try (ResultSet rs = s.executeQuery("SELECT MIN(year), MAX(year) FROM books WHERE year IS NOT NULL")) {
                if (rs.next()) {
                    years.put("min", rs.getObject(1) == null ? null : rs.getInt(1));
                    years.put("max", rs.getObject(2) == null ? null : rs.getInt(2));
                }
            }
            List<Map<String, Object>> authors = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT author, COUNT(*) FROM books WHERE author IS NOT NULL "
                    + "GROUP BY author ORDER BY COUNT(*) DESC, author LIMIT 50")) {
                while (rs.next()) authors.add(Map.of("name", rs.getString(1), "books", rs.getInt(2)));
            }
            out.put("languages", langs);
            out.put("years", years);
            out.put("top_authors", authors);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static BookMetadata row(ResultSet rs) throws SQLException {
        Object year = rs.getObject(5);
        return new BookMetadata(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                year == null ? null : ((Number) year).intValue(), rs.getString(6), rs.getString(7), rs.getString(8));
    }

    private static void rollback(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    @Override
    public void close() {
        for (Connection c : opened) {
            try { c.close(); } catch (SQLException ignored) { }
        }
        opened.clear();
    }
}
