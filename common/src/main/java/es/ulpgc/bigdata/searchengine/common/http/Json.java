package es.ulpgc.bigdata.searchengine.common.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.json.JavalinGson;
import io.javalin.json.JsonMapper;

/** Gson compartido: incluye los null en las respuestas para que el contrato sea estable. */
public final class Json {
    private Json() {}

    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

    public static JsonMapper javalinMapper() {
        return new JavalinGson(GSON, false);
    }
}
