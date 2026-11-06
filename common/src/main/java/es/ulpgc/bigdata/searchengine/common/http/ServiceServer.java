package es.ulpgc.bigdata.searchengine.common.http;

import es.ulpgc.bigdata.searchengine.common.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arranque común de los servicios Javalin.
 *
 * Variables de entorno:
 *   PORT          puerto HTTP (cada servicio tiene su valor por defecto)
 *   HTTP_THREADS  hilos máximos de Jetty para atender peticiones (por defecto 32)
 *   REPLICA_ID    nombre de esta réplica (por defecto &lt;servicio&gt;-&lt;puerto&gt;)
 *   CORS_ORIGINS  orígenes permitidos para un front-end en el navegador (por defecto "*")
 *
 * Todas las respuestas son JSON; los errores tienen siempre la forma
 * {"error": CODIGO, "message": texto, "status": http}.
 */
public final class ServiceServer {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceServer.class);

    private final String service;
    private final int port;
    private final int threads;
    private final String replicaId;
    private final Instant started = Instant.now();
    private Javalin app;

    public ServiceServer(String service, int defaultPort) {
        this.service = service;
        this.port = Config.integer("PORT", defaultPort);
        this.threads = Config.integer("HTTP_THREADS", 32);
        this.replicaId = Config.str("REPLICA_ID", service + "-" + port);
    }

    public String replicaId() { return replicaId; }

    public int threads() { return threads; }

    /** Crea la app; {@code routes} registra las rutas del servicio; {@code status} añade campos a GET /status. */
    public Javalin start(Consumer<Javalin> routes, java.util.function.Supplier<Map<String, Object>> status) {
        String cors = Config.str("CORS_ORIGINS", "*");
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(Json.javalinMapper());
            cfg.http.defaultContentType = "application/json";
            QueuedThreadPool pool = new QueuedThreadPool(Math.max(threads, 4), Math.min(4, threads));
            pool.setName(service + "-http");
            cfg.jetty.threadPool = pool;
            cfg.bundledPlugins.enableCors(c -> c.addRule(rule -> {
                if ("*".equals(cors)) rule.anyHost();
                else for (String o : cors.split(",")) rule.allowHost(o.trim());
            }));
        });
        app.exception(ApiException.class, (e, ctx) -> error(ctx, e.status(), e.code(), e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Error no controlado en {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
        });
        app.error(404, ctx -> {
            if (ctx.result() == null || ctx.result().isEmpty())
                error(ctx, 404, "NOT_FOUND", "Ruta no encontrada: " + ctx.method() + " " + ctx.path());
        });
        app.error(405, ctx -> error(ctx, 405, "METHOD_NOT_ALLOWED", "Método no permitido: " + ctx.method()));
        app.get("/status", ctx -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("service", service);
            s.put("replica_id", replicaId);
            s.put("status", "running");
            s.put("pid", ProcessHandle.current().pid());
            s.put("port", port);
            s.put("http_threads", threads);
            s.put("uptime_s", java.time.Duration.between(started, Instant.now()).toSeconds());
            Runtime rt = Runtime.getRuntime();
            s.put("heap_used_MB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
            s.put("process_cpu_load", processCpuLoad());
            if (status != null) s.putAll(status.get());
            ctx.json(s);
        });
        routes.accept(app);
        app.start(port);
        LOG.info("{} ({}) escuchando en el puerto {} con {} hilos HTTP", service, replicaId, port, threads);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        return app;
    }

    public static void error(Context ctx, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("status", status);
        ctx.status(status).json(body);
    }

    private static Double processCpuLoad() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double v = sun.getProcessCpuLoad();
            return v < 0 ? null : Math.round(v * 1000) / 1000.0;
        }
        return null;
    }

    /** Lee un parámetro de ruta entero positivo o lanza 400. */
    public static int pathInt(Context ctx, String name) {
        String raw = ctx.pathParam(name);
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_BOOK_ID", "'" + raw + "' no es un book_id válido (entero positivo)");
        }
    }
}
