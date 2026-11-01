package es.ulpgc.bigdata.searchengine.common;

import java.nio.charset.StandardCharsets;

/** Libros sintéticos con la estructura de Project Gutenberg para los tests. */
public final class TestBooks {
    private TestBooks() {}

    public static String text(String title, String author, String language, String body) {
        return "﻿The Project Gutenberg eBook of " + title + "\r\n\r\n"
                + "Title: " + title + "\r\n\r\nAuthor: " + author + "\r\n\r\n"
                + "Release date: May 1, 1996 [eBook #521]\r\n\r\nLanguage: " + language + "\r\n\r\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + body + "\r\n*** END OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + "Footer text\r\n";
    }

    public static byte[] bytes(String title, String author, String language, String body) {
        return text(title, author, language, body).getBytes(StandardCharsets.UTF_8);
    }
}
