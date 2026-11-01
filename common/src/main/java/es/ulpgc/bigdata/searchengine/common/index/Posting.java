package es.ulpgc.bigdata.searchengine.common.index;

/** Aparición de un término en un libro: id del libro y frecuencia del término (tf). */
public record Posting(int bookId, int tf) {}
