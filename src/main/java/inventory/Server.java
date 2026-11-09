package inventory;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static inventory.Models.*;

/** The only component with MariaDB access. Put HTTPS in front of this service
 * for remote clients; its default listener is loopback only. */
public final class Server implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final Database database;
    private final byte[] token;
    private final String actor;
    private final HttpServer http;
    private final ExecutorService workers;

    public Server(Database database, String host, int port, String token, String actor) throws IOException {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("API token must contain at least 32 characters.");
        this.database = database; this.token = token.getBytes(StandardCharsets.UTF_8);
        this.actor = Models.text(actor, "Operator", 100, false);
        http = HttpServer.create(new InetSocketAddress(host, port), 64);
        workers = new ThreadPoolExecutor(4, 12, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());
        http.setExecutor(workers);
        http.createContext("/", this::handle);
    }

    public void start() { http.start(); }
    public int port() { return http.getAddress().getPort(); }
    @Override public void close() { http.stop(0); workers.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/health") && method.equals("GET")) {
                database.ping(); respond(exchange, 200, Map.of("status", "ok")); return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!MessageDigest.isEqual(token, supplied)) throw new Problem(401, "Invalid API token.");
            Object response;
            int status = 200;
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/api/products")) {
                if (method.equals("GET")) {
                    String search = Models.text(query.getOrDefault("q", ""), "Search", 200, true);
                    response = database.list(search, Boolean.parseBoolean(query.get("lowStock")));
                } else if (method.equals("POST")) { response = database.create(body(exchange, ProductInput.class), actor); status = 201; }
                else throw new Problem(405, "Use GET or POST.");
            } else if (path.matches("/api/products/[1-9][0-9]*(/(adjust|archive))?")) {
                String[] segments = path.split("/");
                long id = Long.parseLong(segments[3]);
                if (segments.length == 4 && method.equals("GET")) response = database.get(id);
                else if (segments.length == 4 && method.equals("PUT")) response = database.update(id, body(exchange, ProductInput.class));
                else if (segments.length == 5 && segments[4].equals("adjust") && method.equals("POST"))
                    response = database.adjust(id, body(exchange, Adjustment.class), actor);
                else if (segments.length == 5 && segments[4].equals("archive") && method.equals("POST")) {
                    JsonObject input = body(exchange, JsonObject.class);
                    if (!input.has("version") || !input.get("version").isJsonPrimitive()
                        || !input.get("version").getAsString().matches("[0-9]+")) throw new Problem(400, "A nonnegative version is required.");
                    database.archive(id, Integer.parseInt(input.get("version").getAsString())); response = Map.of("archived", true);
                } else throw new Problem(405, "Unsupported product operation.");
            } else if (path.equals("/api/movements") && method.equals("GET")) {
                long id = Long.parseLong(query.getOrDefault("productId", "0"));
                if (id < 0) throw new Problem(400, "Invalid productId.");
                response = database.history(id);
            } else throw new Problem(404, "API route not found.");
            respond(exchange, status, response);
        } catch (Problem error) { respond(exchange, error.status, Map.of("error", error.getMessage())); }
        catch (JsonParseException | IllegalArgumentException error) { respond(exchange, 400, Map.of("error", "Invalid JSON or request parameter.")); }
        catch (SQLException error) {
            // Do not return SQL, connection strings, or credentials to the client.
            boolean duplicate = error.getErrorCode() == 1062;
            if (!duplicate) System.err.println("Database request failed; SQL state=" + error.getSQLState());
            respond(exchange, duplicate ? 409 : 503, Map.of("error", duplicate ? "SKU is already in use (including archived products)." : "Database unavailable. Please try again."));
        } catch (Exception error) {
            System.err.println("Request failed: " + error.getClass().getSimpleName());
            respond(exchange, 500, Map.of("error", "The server could not complete this request."));
        } finally { exchange.close(); }
    }

    private static <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new Problem(415, "Content-Type must be application/json.");
        byte[] bytes = exchange.getRequestBody().readNBytes(32769);
        if (bytes.length > 32768) throw new Problem(413, "Request body is too large.");
        JsonElement document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!document.isJsonObject()) throw new Problem(400, "A JSON object is required.");
        T value = JSON.fromJson(document, type);
        if (value == null) throw new Problem(400, "A request body is required.");
        return value;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String setting(Properties properties, String key, String environment, String fallback) {
        String value = System.getenv(environment);
        return value == null ? properties.getProperty(key, fallback) : value;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "30");
        Properties config = new Properties();
        Path path = Path.of(args.length == 0 ? "server.properties" : args[0]);
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path)) { config.load(reader); }
        String url = setting(config, "db.url", "INVENTORY_DB_URL", "jdbc:mariadb://127.0.0.1:3306/inventory?connectTimeout=5000&socketTimeout=10000&timezone=UTC");
        Database database = new Database(url, setting(config, "db.user", "INVENTORY_DB_USER", "inventory"),
            setting(config, "db.password", "INVENTORY_DB_PASSWORD", ""));
        database.initialize();
        Server server = new Server(database, setting(config, "http.host", "INVENTORY_HOST", "127.0.0.1"),
            Integer.parseInt(setting(config, "http.port", "INVENTORY_PORT", "8080")),
            setting(config, "api.token", "INVENTORY_API_TOKEN", ""), setting(config, "api.operator", "INVENTORY_OPERATOR", "Operator"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Inventory server listening on port " + server.port() + ". Database ready.");
    }
}
