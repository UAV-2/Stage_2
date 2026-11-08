package es.ulpgc.bigdata.searchengine.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Textos sintéticos deterministas (misma semilla = mismos datos) con una distribución de
 * palabras tipo Zipf, para que los microbenchmarks no dependan de la red ni de ficheros.
 */
final class SyntheticCorpus {
    private SyntheticCorpus() {}

    private static final String[] SYLLABLES = {"ka", "lo", "mi", "ne", "ru", "ta", "shi", "vo", "den", "gar",
            "bel", "tor", "quin", "ex", "ul", "ar", "is", "om", "en", "st", "tr", "wy", "zel", "cra", "mon"};
    static final String[] COMMON = {"the", "and", "of", "to", "in", "that", "it", "was", "he", "his",
            "adventure", "island", "sea", "ship", "captain", "love", "war", "peace", "king", "queen"};

    static List<String> vocabulary(int size, long seed) {
        Random r = new Random(seed);
        List<String> v = new ArrayList<>(List.of(COMMON));
        while (v.size() < size) {
            StringBuilder sb = new StringBuilder();
            int n = 2 + r.nextInt(3);
            for (int i = 0; i < n; i++) sb.append(SYLLABLES[r.nextInt(SYLLABLES.length)]);
            v.add(sb.toString());
        }
        return v;
    }

    /** Un "libro" de {@code words} palabras con mayúsculas y puntuación. */
    static String book(List<String> vocab, int words, long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder(words * 7);
        for (int i = 0; i < words; i++) {
            // rango ~ Zipf: índices bajos mucho más frecuentes
            int idx = (int) Math.min(vocab.size() - 1, Math.floor(Math.pow(vocab.size(), r.nextDouble())) - 1);
            String w = vocab.get(Math.max(0, idx));
            if (i % 12 == 0) sb.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
            else sb.append(w);
            sb.append(i % 12 == 11 ? ".\n" : " ");
        }
        return sb.toString();
    }

    static String header(int id, long seed) {
        Random r = new Random(seed);
        String[] authors = {"Daniel Defoe", "Jane Austen", "Herman Melville", "Mary Shelley", "Jules Verne"};
        String[] langs = {"English", "English", "English", "French", "Spanish"};
        return "The Project Gutenberg eBook of Book " + id + "\n\nTitle: Book " + id + "\n\nAuthor: "
                + authors[r.nextInt(authors.length)] + "\n\nRelease date: May 1, " + (1994 + r.nextInt(30))
                + " [eBook #" + id + "]\n\nLanguage: " + langs[r.nextInt(langs.length)]
                + "\n\nOriginal publication: " + (1700 + r.nextInt(220)) + "\n\nCredits: synthetic";
    }
}
