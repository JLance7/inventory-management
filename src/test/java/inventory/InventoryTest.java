package inventory;

import com.google.gson.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import static inventory.Models.*;

/** Plain Java tests: no test framework or fake database is required. */
public final class InventoryTest {
    static int assertions;
    static final Gson JSON = new Gson();
    static final String TOKEN = "inventory-test-token-with-at-least-32-characters";
    static String base;

    public static void main(String[] args) throws Exception {
        ProductInput input = input(" abc ", 10);
        input.validate(); check(input.sku.equals("ABC"), "SKU normalization");
        invalid(() -> { ProductInput p = input("BAD", 0); p.unitPrice = new BigDecimal("1.001"); p.validate(); }, "Fractional cents rejected");
        invalid(() -> { ProductInput p = input("BAD", -1); p.validate(); }, "Negative opening stock rejected");
        invalid(() -> { ProductInput p = input("", 0); p.validate(); }, "Empty SKU rejected");
        invalid(() -> { ProductInput p = input("BAD", 0); p.unitPrice = null; p.validate(); }, "Null price rejected");
        invalid(() -> { Adjustment a = adjustment(0, "Reason"); a.validate(); }, "Zero adjustment rejected");
        invalid(() -> { Adjustment a = adjustment(1, ""); a.validate(); }, "Audit reason required");
        invalid(() -> { Adjustment a = adjustment(1, "Reason"); a.requestId = "bad"; a.validate(); }, "Request ID validated");
        check(DesktopApp.cell("=SUM(A1)").equals("\"'=SUM(A1)\""), "CSV formula injection neutralized");
        invalid(() -> new ApiClient("http://example.com", TOKEN.toCharArray(), false), "Remote HTTP blocked by default");
        invalid(() -> new ApiClient("https://example.com/path", TOKEN.toCharArray(), false), "Server origin validation");
        invalid(() -> new ApiClient("https://example.com", "short".toCharArray(), false), "Short credentials rejected");

        String url = System.getenv("TEST_DB_URL");
        if (url == null) { System.out.println("PASS: " + assertions + " unit assertions; database integration skipped (run integration-test.ps1)."); return; }
        Database db = new Database(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
        db.initialize();
        try (Server server = new Server(db, "127.0.0.1", 0, TOKEN, "Test operator")) {
            server.start(); base = "http://127.0.0.1:" + server.port();
            check(raw("GET", "/api/health", null, false).statusCode() == 200, "Health route checks database");
            check(raw("GET", "/api/products", null, false).statusCode() == 401, "Inventory requires authentication");
            Product p = decode(raw("POST", "/api/products", JSON.toJson(input("WIDGET", 10)), true), 201, Product.class);
            check(p.quantity == 10 && p.version == 0, "Create product and opening balance");
            check(raw("POST", "/api/products", JSON.toJson(input("WIDGET", 0)), true).statusCode() == 409, "Duplicate SKU conflict");
            check(raw("POST", "/api/products", "null", true).statusCode() == 400, "Null JSON rejected");
            check(raw("POST", "/api/products", "{", true).statusCode() == 400, "Malformed JSON rejected");
            check(raw("POST", "/api/products", "{\"name\":\"" + "a".repeat(33000) + "\"}", true).statusCode() == 413, "Oversized body rejected");
            check(raw("GET", "/api/products?q=%27%20OR%201%3D1%20--", null, true).body().equals("[]"), "Search is parameterized");

            Adjustment receive = adjustment(5, "Purchase order 1001");
            Movement first = decode(raw("POST", "/api/products/" + p.id + "/adjust", JSON.toJson(receive), true), 200, Movement.class);
            Movement replay = decode(raw("POST", "/api/products/" + p.id + "/adjust", JSON.toJson(receive), true), 200, Movement.class);
            check(first.id == replay.id && db.get(p.id).quantity == 15, "Idempotent retry changes stock exactly once");
            receive.delta = 6;
            check(raw("POST", "/api/products/" + p.id + "/adjust", JSON.toJson(receive), true).statusCode() == 409, "Request ID cannot be repurposed");
            check(db.history(p.id).size() == 2, "Opening and receipt audit entries");
            ProductInput stale = input("WIDGET", 0); stale.version = p.version;
            check(raw("PUT", "/api/products/" + p.id, JSON.toJson(stale), true).statusCode() == 409, "Stale metadata edit rejected");
            stale.version = db.get(p.id).version; stale.location = "Shelf B";
            Product updated = decode(raw("PUT", "/api/products/" + p.id, JSON.toJson(stale), true), 200, Product.class);
            check(updated.location.equals("Shelf B") && updated.quantity == 15, "Metadata update preserves stock");
            check(raw("POST", "/api/products/" + p.id + "/archive", "{\"version\":" + updated.version + "}", true).statusCode() == 409, "Cannot archive nonzero stock");
            check(raw("POST", "/api/products/" + p.id + "/adjust", JSON.toJson(adjustment(-16, "Too many")), true).statusCode() == 409, "Oversell rejected");
            check(db.get(p.id).quantity == 15 && db.history(p.id).size() == 2, "Rejected transaction leaves balance and audit unchanged");

            Product concurrent = decode(raw("POST", "/api/products", JSON.toJson(input("CONCURRENT", 10)), true), 201, Product.class);
            ExecutorService pool = Executors.newFixedThreadPool(12);
            try {
                AtomicInteger success = new AtomicInteger(), conflict = new AtomicInteger();
                List<Future<?>> requests = new ArrayList<>();
                for (int i = 0; i < 20; i++) requests.add(pool.submit(() -> {
                    try {
                        int status = raw("POST", "/api/products/" + concurrent.id + "/adjust", JSON.toJson(adjustment(-1, "Concurrent issue")), true).statusCode();
                        if (status == 200) success.incrementAndGet(); else if (status == 409) conflict.incrementAndGet(); else throw new AssertionError("Unexpected status " + status);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
                for (Future<?> request : requests) request.get(30, TimeUnit.SECONDS);
                check(success.get() == 10 && conflict.get() == 10 && db.get(concurrent.id).quantity == 0, "20 concurrent issues: exactly 10 succeed, no overselling");
                check(db.history(concurrent.id).size() == 11, "Concurrent changes each have one audit row");
                check(db.history(concurrent.id).stream().mapToInt(m -> m.delta).sum() == db.get(concurrent.id).quantity, "Audit sum equals current stock");
            } finally { pool.shutdownNow(); }

            Product current = db.get(concurrent.id);
            decode(raw("POST", "/api/products/" + concurrent.id + "/archive", "{\"version\":" + current.version + "}", true), 200, JsonObject.class);
            check(db.get(concurrent.id).archived && db.history(concurrent.id).size() == 11, "Archive preserves audit history");
            check(raw("POST", "/api/products/" + concurrent.id + "/adjust", JSON.toJson(adjustment(1, "Late receipt")), true).statusCode() == 409, "Archived inventory cannot change");
            check(db.list("CONCURRENT", false).isEmpty(), "Archived rows excluded from active inventory");
            check(!db.list("WIDGET", true).isEmpty(), "Low-stock filter includes threshold matches");
            try (Server second = new Server(db, "127.0.0.1", 0, TOKEN, "Second operator")) {
                second.start();
                ApiClient client = new ApiClient("http://127.0.0.1:" + second.port(), TOKEN.toCharArray(), false);
                check(client.products("WIDGET", false)[0].quantity == 15, "Second backend instance reads persisted MariaDB data");
                client.clearCredentials();
            }
            if (Arrays.asList(args).contains("--ui")) renderDesktop();
        }
        String report = "PASS: " + assertions + " assertions, including real MariaDB HTTP integration and concurrent stock changes.\n";
        Files.createDirectories(Path.of("build")); Files.writeString(Path.of("build/test-results.txt"), report);
        System.out.print(report);
    }

    private static void renderDesktop() throws Exception {
        java.util.prefs.Preferences preferences = java.util.prefs.Preferences.userNodeForPackage(DesktopApp.class);
        String originalServerUrl = preferences.get("serverUrl", null);
        final DesktopApp[] frame = new DesktopApp[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new DesktopApp();
            try {
                ((JTextField) field(frame[0], "address")).setText(base);
                ((JPasswordField) field(frame[0], "token")).setText(TOKEN);
                ((JButton) field(frame[0], "connect")).doClick();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        long deadline = System.currentTimeMillis() + 15000;
        boolean[] loaded = {false};
        while (!loaded[0] && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> {
                try { loaded[0] = ((JLabel) field(frame[0], "summary")).getText().contains("PRODUCTS SHOWN"); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
        }
        try {
            check(loaded[0], "Swing client connects asynchronously and displays database inventory");
            boolean[] controls = {false};
            SwingUtilities.invokeAndWait(() -> {
                try {
                    java.util.List<?> buttons = (java.util.List<?>) field(frame[0], "actions");
                    controls[0] = buttons.stream().allMatch(button -> ((JButton) button).isEnabled());
                    ((JTable) field(frame[0], "table")).setRowSelectionInterval(0, 0);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            check(controls[0], "Connected desktop enables product and stock actions");
            long historyDeadline = System.currentTimeMillis() + 10000;
            boolean[] historyReady = {false};
            while (!historyReady[0] && System.currentTimeMillis() < historyDeadline) {
                Thread.sleep(100);
                SwingUtilities.invokeAndWait(() -> {
                    try { historyReady[0] = ((JTable) field(frame[0], "history")).getRowCount() == 2; }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
            }
            check(historyReady[0], "Selecting a product loads its audit history");
            SwingUtilities.invokeAndWait(() -> {
                java.awt.Container content = frame[0].getContentPane();
                content.setSize(1340, 820); layout(content);
                BufferedImage image = new BufferedImage(1340, 820, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
                try { ImageIO.write(image, "png", Path.of("build/desktop-preview.png").toFile()); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
            if (originalServerUrl == null) preferences.remove("serverUrl");
            else preferences.put("serverUrl", originalServerUrl);
        }
    }
    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents())
            if (child instanceof java.awt.Container) layout((java.awt.Container) child);
    }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static ProductInput input(String sku, int quantity) {
        ProductInput p = new ProductInput(); p.sku = sku; p.name = "Workshop widget"; p.category = "Hardware"; p.location = "Shelf A";
        p.initialQuantity = quantity; p.reorderLevel = 15; p.unitPrice = new BigDecimal("12.50"); return p;
    }
    private static Adjustment adjustment(int delta, String reason) { Adjustment a = new Adjustment(); a.delta = delta; a.reason = reason; a.requestId = UUID.randomUUID().toString(); return a; }
    private static HttpResponse<String> raw(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(20));
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        if (body != null) request.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static <T> T decode(HttpResponse<String> response, int expected, Class<T> type) {
        check(response.statusCode() == expected, "HTTP " + expected + " received (actual " + response.statusCode() + ")");
        return JSON.fromJson(response.body(), type);
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); assertions++; System.out.println("PASS " + name); }
    private static void invalid(Runnable action, String name) { try { action.run(); } catch (IllegalArgumentException | Problem expected) { check(true, name); return; } throw new AssertionError(name); }
}
