package es.ulpgc.bigdata.searchengine.common;

/**
 * Metadatos de un libro. {@code year} es el año de publicación original si el header lo
 * indica ("Original publication: ..."); si no, el año de publicación en Gutenberg
 * ("Release date: ..."). {@code yearSource} dice de cuál de los dos viene.
 */
public record BookMetadata(int bookId, String title, String author, String language,
                           Integer year, String yearSource, String releaseDate, String bodyPath) {

    public BookMetadata withBookIdAndPath(int id, String path) {
        return new BookMetadata(id, title, author, language, year, yearSource, releaseDate, path);
    }
}
