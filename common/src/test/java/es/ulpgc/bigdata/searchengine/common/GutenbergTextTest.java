package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GutenbergTextTest {

    @Test
    void splitsHeaderAndBodyAndDropsFooter() {
        var parts = GutenbergText.split(TestBooks.bytes("Robinson Crusoe", "Daniel Defoe", "English",
                "Line one.\r\nLine two.")).orElseThrow();
        assertTrue(parts.header().startsWith("The Project Gutenberg eBook"));
        assertTrue(parts.header().endsWith("Language: English"));
        assertEquals("Line one.\nLine two.", parts.body());
    }

    @Test
    void acceptsOldThisMarkerCaseInsensitive() {
        String t = TestBooks.text("X", "Y", "English", "b").replace("START OF THE", "start of this");
        assertTrue(GutenbergText.split(t.getBytes(StandardCharsets.UTF_8)).isPresent());
    }

    @Test
    void missingMarkersGiveEmpty() {
        assertTrue(GutenbergText.split("no markers".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }
}
