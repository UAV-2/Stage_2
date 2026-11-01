package es.ulpgc.bigdata.searchengine.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tokenización del Stage 1: minúsculas solo para A-Z, tokens = secuencias [a-z]+,
 * longitud >= 2 y sin stopwords. Es inmutable y segura para varios hilos.
 */
public final class Tokenizer {
    private final Set<String> stopwords;

    public Tokenizer(Set<String> stopwords) {
        this.stopwords = Set.copyOf(stopwords);
    }

    public static Tokenizer fromFile(Path stopwordsFile) {
        try {
            Set<String> words = new HashSet<>();
            for (String line : Files.readAllLines(stopwordsFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) words.add(line.strip());
            }
            return new Tokenizer(words);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + stopwordsFile, e);
        }
    }

    public static Tokenizer withoutStopwords() {
        return new Tokenizer(Collections.emptySet());
    }

    public List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        StringBuilder sb = new StringBuilder();
        while (i < n) {
            char c = lower(text.charAt(i));
            if (c >= 'a' && c <= 'z') {
                sb.setLength(0);
                while (i < n) {
                    char d = lower(text.charAt(i));
                    if (d < 'a' || d > 'z') break;
                    sb.append(d);
                    i++;
                }
                if (sb.length() >= 2) {
                    String token = sb.toString();
                    if (!stopwords.contains(token)) out.add(token);
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /** Frecuencia de cada término en el texto (para ordenar resultados por relevancia). */
    public Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokenize(text)) tf.merge(t, 1, Integer::sum);
        return tf;
    }

    /** Términos distintos de una consulta, en orden de aparición. */
    public List<String> queryTerms(String query) {
        return new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
    }

    private static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }
}
