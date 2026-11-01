package es.ulpgc.bigdata.searchengine.common;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrae los metadatos del header línea a línea (primera coincidencia de cada campo). */
public final class HeaderParser {
    private HeaderParser() {}

    private static final int F = Pattern.CASE_INSENSITIVE;
    private static final Pattern TITLE = Pattern.compile("^Title:\\s*(.+)$", F);
    private static final Pattern AUTHOR = Pattern.compile("^Author:\\s*(.+)$", F);
    private static final Pattern LANGUAGE = Pattern.compile("^Language:\\s*(.+)$", F);
    private static final Pattern RELEASE = Pattern.compile("^Release date:\\s*(.+?)(?:\\s*\\[.*)?$", F);
    private static final Pattern ORIGINAL = Pattern.compile("^Original publication:\\s*(.+)$", F);
    private static final Pattern YEAR = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");

    private static final Map<String, String> LANG = Map.ofEntries(
            Map.entry("english", "en"), Map.entry("spanish", "es"), Map.entry("french", "fr"),
            Map.entry("german", "de"), Map.entry("italian", "it"), Map.entry("portuguese", "pt"),
            Map.entry("dutch", "nl"), Map.entry("finnish", "fi"), Map.entry("latin", "la"),
            Map.entry("chinese", "zh"), Map.entry("polish", "pl"), Map.entry("swedish", "sv"),
            Map.entry("greek", "el"), Map.entry("russian", "ru"), Map.entry("danish", "da"));

    public static BookMetadata parse(int bookId, String header) {
        String title = null, author = null, language = null, release = null, original = null;
        for (String line : header.split("\n")) {
            if (title == null) title = match(TITLE, line);
            if (author == null) author = match(AUTHOR, line);
            if (language == null) language = match(LANGUAGE, line);
            if (release == null) release = match(RELEASE, line);
            if (original == null) original = match(ORIGINAL, line);
        }
        Integer year = firstYear(original);
        String source = year != null ? "original_publication" : null;
        if (year == null) {
            year = firstYear(release);
            if (year != null) source = "gutenberg_release";
        }
        return new BookMetadata(bookId, title, author, normalizeLanguage(language), year, source, release, null);
    }

    /** "English" -> "en"; "English, French" -> "en" (primer idioma); desconocido -> minúsculas. */
    public static String normalizeLanguage(String value) {
        if (value == null) return null;
        String first = value.split("[,;]")[0].strip().toLowerCase(Locale.ROOT);
        if (first.isEmpty()) return null;
        return LANG.getOrDefault(first, first);
    }

    private static String match(Pattern p, String line) {
        Matcher m = p.matcher(line.strip());
        if (!m.matches()) return null;
        String v = m.group(1).strip();
        return v.isEmpty() ? null : v;
    }

    private static Integer firstYear(String s) {
        if (s == null) return null;
        Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
