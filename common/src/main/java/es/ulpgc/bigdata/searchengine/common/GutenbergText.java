package es.ulpgc.bigdata.searchengine.common;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separación header/body de un libro de Project Gutenberg (mismas reglas que en el Stage 1). */
public final class GutenbergText {
    private GutenbergText() {}

    private static final Pattern START = Pattern.compile(
            "\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*\\n", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile(
            "\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK", Pattern.CASE_INSENSITIVE);

    public record Parts(String header, String body) {}

    /** UTF-8 (bytes inválidos reemplazados) y saltos de línea \r\n normalizados a \n. */
    public static String decode(byte[] raw) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(raw)).toString();
            if (text.startsWith("﻿")) text = text.substring(1);
            return text.replace("\r\n", "\n");
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Header (antes del START) y body (entre START y END); vacío si faltan los marcadores. */
    public static Optional<Parts> split(byte[] raw) {
        String text = decode(raw);
        Matcher s = START.matcher(text);
        if (!s.find()) return Optional.empty();
        Matcher e = END.matcher(text);
        if (!e.find(s.end())) return Optional.empty();
        return Optional.of(new Parts(text.substring(0, s.start()).strip(),
                text.substring(s.end(), e.start()).strip()));
    }
}
