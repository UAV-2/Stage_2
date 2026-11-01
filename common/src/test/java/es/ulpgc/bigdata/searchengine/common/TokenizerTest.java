package es.ulpgc.bigdata.searchengine.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenizerTest {
    private final Tokenizer tok = new Tokenizer(Set.of("the", "and"));

    @Test
    void stageOneRules() {
        assertEquals(List.of("don"), tok.tokenize("don't"));
        assertEquals(List.of("well", "known"), tok.tokenize("well-known"));
        assertEquals(List.of("caf"), tok.tokenize("CAFÉ"));
        assertEquals(List.of("chapter"), tok.tokenize("chapter12 x_y 1999"));
    }

    @Test
    void stopwordsAndFrequencies() {
        assertEquals(Map.of("sea", 2, "ship", 1), tok.termFrequencies("The sea and the SEA ship"));
    }

    @Test
    void queryTermsAreUnique() {
        assertEquals(List.of("whale", "sea"), tok.queryTerms("Whale whale the SEA"));
    }
}
