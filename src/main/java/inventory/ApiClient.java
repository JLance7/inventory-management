package inventory;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import static inventory.Models.*;

/** Desktop transport. Calls are made from SwingWorker, never the UI thread. */
public final class ApiClient {
    private static final Gson JSON = new Gson();
    private final HttpClient http;
    private final URI base;
    private final char[] token;

    public ApiClient(String address, char[] token, boolean allowInsecure) {
        URI uri = URI.create(address.strip().replaceAll("/+$", ""));
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Use a server origin such as https://inventory.example.com (no path or credentials).");
        boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!uri.getScheme().equalsIgnoreCase("https") && !(uri.getScheme().equalsIgnoreCase("http") && (loopback || allowInsecure)))
            throw new IllegalArgumentException("Remote connections require HTTPS. HTTP on a trusted private LAN must be explicitly enabled.");
        if (token.length < 32) throw new IllegalArgumentException("Enter the API token generated on the server (at least 32 characters).");
        this.base = uri; this.token = token.clone();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void clearCredentials() { Arrays.fill(token, '\0'); }
    public Product[] products(String search, boolean low) throws Exception {
        return request("GET", "/api/products?q=" + URLEncoder.encode(search, StandardCharsets.UTF_8) + "&lowStock=" + low, null, Product[].class);
    }
    public Product save(Product product, ProductInput input) throws Exception {
        return request(product == null ? "POST" : "PUT", product == null ? "/api/products" : "/api/products/" + product.id, input, Product.class);
    }
    public Movement adjust(long id, Adjustment adjustment) throws Exception {
        return request("POST", "/api/products/" + id + "/adjust", adjustment, Movement.class);
    }
    public Movement[] history(long id) throws Exception { return request("GET", "/api/movements?productId=" + id, null, Movement[].class); }
    public void archive(Product product) throws Exception {
        request("POST", "/api/products/" + product.id + "/archive", java.util.Map.of("version", product.version), JsonObject.class);
    }

    public <T> T request(String method, String path, Object body, Class<T> result) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String message = "Server returned HTTP " + response.statusCode();
            try { message = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString(); }
            catch (RuntimeException ignored) { /* A reverse proxy might return HTML. */ }
            throw new IOException(message);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Unexpected server response.");
        return JSON.fromJson(response.body(), result);
    }
}
