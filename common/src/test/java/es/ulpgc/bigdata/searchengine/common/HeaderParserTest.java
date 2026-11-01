package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeaderParserTest {

    @Test
    void parsesFieldsAndReleaseYear() {
        var parts = GutenbergText.split(TestBooks.bytes("Robinson Crusoe", "Daniel Defoe", "English", "x")).orElseThrow();
        BookMetadata m = HeaderParser.parse(521, parts.header());
        assertEquals("Robinson Crusoe", m.title());
        assertEquals("Daniel Defoe", m.author());
        assertEquals("en", m.language());
        assertEquals(1996, m.year());
        assertEquals("gutenberg_release", m.yearSource());
        assertEquals("May 1, 1996", m.releaseDate());
    }

    @Test
    void prefersOriginalPublicationYear() {
        BookMetadata m = HeaderParser.parse(1, "Title: X\nRelease date: May 1, 1996\nOriginal publication: London: W. Taylor, 1719\n");
        assertEquals(1719, m.year());
        assertEquals("original_publication", m.yearSource());
    }

    @Test
    void missingFieldsAreNullAndLanguageNormalized() {
        BookMetadata m = HeaderParser.parse(1, "title: only a title\nLanguage: Finnish");
        assertNull(m.author());
        assertNull(m.year());
        assertEquals("fi", m.language());
        assertEquals("en", HeaderParser.normalizeLanguage("English, French"));
        assertEquals("klingon", HeaderParser.normalizeLanguage("Klingon"));
    }
}
