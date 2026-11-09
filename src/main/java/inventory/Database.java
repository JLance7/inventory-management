package inventory;

import java.sql.*;
import java.util.*;
import static inventory.Models.*;

/** JDBC repository. All stock changes lock the product and commit its audit row
 * in the same transaction; concurrent clients cannot oversell or lose updates. */
public final class Database {
    private final String url, user, password;

    public Database(String url, String user, String password) {
        this.url = url; this.user = user; this.password = password;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initialize() throws SQLException {
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS products ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, sku VARCHAR(64) NOT NULL UNIQUE,"
                + "name VARCHAR(200) NOT NULL, category VARCHAR(100) NOT NULL DEFAULT '',"
                + "location VARCHAR(100) NOT NULL DEFAULT '', unit_price DECIMAL(12,2) NOT NULL,"
                + "quantity INT NOT NULL DEFAULT 0 CHECK(quantity >= 0 AND quantity <= 1000000),"
                + "reorder_level INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,"
                + "archived BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS movements ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL,"
                + "delta INT NOT NULL, balance INT NOT NULL, reason VARCHAR(250) NOT NULL,"
                + "actor VARCHAR(100) NOT NULL, request_id CHAR(36) NOT NULL UNIQUE,"
                + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                + "CONSTRAINT fk_movement_product FOREIGN KEY(product_id) REFERENCES products(id),"
                + "INDEX ix_product_history(product_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    public void ping() throws SQLException {
        try (Connection connection = connect(); Statement s = connection.createStatement()) { s.execute("SELECT 1"); }
    }

    public List<Product> list(String search, boolean lowStock) throws SQLException {
        String sql = "SELECT * FROM products WHERE archived=FALSE"
            + (lowStock ? " AND quantity<=reorder_level" : "")
            + " AND (LOCATE(?,sku)>0 OR LOCATE(?,name)>0 OR LOCATE(?,category)>0 OR LOCATE(?,location)>0) ORDER BY name,id LIMIT 2000";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) statement.setString(i, search);
            try (ResultSet rows = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (rows.next()) products.add(product(rows));
                return products;
            }
        }
    }

    public Product get(long id) throws SQLException {
        try (Connection connection = connect()) { return get(connection, id, false); }
    }

    private Product get(Connection connection, long id, boolean lock) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT * FROM products WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setLong(1, id);
            try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) throw new Problem(404, "Product not found.");
                return product(rows);
            }
        }
    }

    public Product create(ProductInput input, String actor) throws SQLException {
        input.validate();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement s = connection.prepareStatement("INSERT INTO products(sku,name,category,location,unit_price,quantity,reorder_level) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    metadata(s, input); s.setInt(6, input.initialQuantity); s.setInt(7, input.reorderLevel);
                    s.executeUpdate();
                    try (ResultSet keys = s.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
                }
                insertMovement(connection, id, input.initialQuantity, input.initialQuantity, "Opening balance", actor, UUID.randomUUID().toString());
                Product product = get(connection, id, false);
                connection.commit();
                return product;
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    public Product update(long id, ProductInput input) throws SQLException {
        input.validate();
        try (Connection connection = connect(); PreparedStatement s = connection.prepareStatement(
            "UPDATE products SET sku=?,name=?,category=?,location=?,unit_price=?,reorder_level=?,version=version+1 WHERE id=? AND version=? AND archived=FALSE")) {
            metadata(s, input); s.setInt(6, input.reorderLevel); s.setLong(7, id); s.setInt(8, input.version);
            if (s.executeUpdate() == 0) throw new Problem(409, "Product changed or was archived. Refresh before editing.");
            return get(connection, id, false);
        }
    }

    public void archive(long id, int version) throws SQLException {
        try (Connection connection = connect(); PreparedStatement s = connection.prepareStatement(
            "UPDATE products SET archived=TRUE,version=version+1 WHERE id=? AND version=? AND quantity=0 AND archived=FALSE")) {
            s.setLong(1, id); s.setInt(2, version);
            if (s.executeUpdate() == 0) throw new Problem(409, "Only unchanged products with zero stock can be archived. Refresh first.");
        }
    }

    public Movement adjust(long id, Adjustment adjustment, String actor) throws SQLException {
        adjustment.validate();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // Consistent lock order serializes updates for this product.
                Product product = get(connection, id, true);
                Movement previous = findMovement(connection, adjustment.requestId);
                if (previous != null) {
                    verifyReplay(previous, id, adjustment);
                    connection.commit(); return previous;
                }
                if (product.archived) throw new Problem(409, "Archived products cannot be adjusted.");
                long balance = (long) product.quantity + adjustment.delta;
                if (balance < 0 || balance > 1_000_000) throw new Problem(409, "Change would make stock negative or exceed 1,000,000.");
                try (PreparedStatement s = connection.prepareStatement("UPDATE products SET quantity=?,version=version+1 WHERE id=?")) {
                    s.setInt(1, (int) balance); s.setLong(2, id); s.executeUpdate();
                }
                insertMovement(connection, id, adjustment.delta, (int) balance, adjustment.reason, actor, adjustment.requestId);
                Movement result = findMovement(connection, adjustment.requestId);
                connection.commit(); return result;
            } catch (SQLException error) {
                connection.rollback();
                // The same UUID may have raced on another product. A committed
                // retry must match the original operation rather than change stock.
                if (error.getErrorCode() == 1062) {
                    try (Connection replay = connect()) {
                        Movement previous = findMovement(replay, adjustment.requestId);
                        if (previous != null) { verifyReplay(previous, id, adjustment); return previous; }
                    }
                }
                throw error;
            } catch (RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    private static void verifyReplay(Movement previous, long id, Adjustment input) {
        if (previous.productId != id || previous.delta != input.delta || !previous.reason.equals(input.reason))
            throw new Problem(409, "This requestId already belongs to a different stock change.");
    }

    public List<Movement> history(long productId) throws SQLException {
        String sql = "SELECT m.*,p.sku,p.name FROM movements m JOIN products p ON p.id=m.product_id"
            + (productId > 0 ? " WHERE m.product_id=?" : "") + " ORDER BY m.id DESC LIMIT 500";
        try (Connection connection = connect(); PreparedStatement s = connection.prepareStatement(sql)) {
            if (productId > 0) s.setLong(1, productId);
            try (ResultSet rows = s.executeQuery()) {
                List<Movement> result = new ArrayList<>();
                while (rows.next()) result.add(movement(rows));
                return result;
            }
        }
    }

    private Movement findMovement(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT m.*,p.sku,p.name FROM movements m JOIN products p ON p.id=m.product_id WHERE request_id=?")) {
            s.setString(1, requestId);
            try (ResultSet rows = s.executeQuery()) { return rows.next() ? movement(rows) : null; }
        }
    }

    private static void insertMovement(Connection connection, long id, int delta, int balance, String reason, String actor, String requestId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO movements(product_id,delta,balance,reason,actor,request_id) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setInt(2, delta); s.setInt(3, balance); s.setString(4, reason); s.setString(5, actor); s.setString(6, requestId); s.executeUpdate();
        }
    }

    private static void metadata(PreparedStatement s, ProductInput p) throws SQLException {
        s.setString(1, p.sku); s.setString(2, p.name); s.setString(3, p.category); s.setString(4, p.location); s.setBigDecimal(5, p.unitPrice);
    }
    private static Product product(ResultSet rows) throws SQLException {
        Product p = new Product();
        p.id = rows.getLong("id"); p.sku = rows.getString("sku"); p.name = rows.getString("name");
        p.category = rows.getString("category"); p.location = rows.getString("location"); p.unitPrice = rows.getBigDecimal("unit_price");
        p.quantity = rows.getInt("quantity"); p.reorderLevel = rows.getInt("reorder_level"); p.version = rows.getInt("version"); p.archived = rows.getBoolean("archived");
        return p;
    }
    private static Movement movement(ResultSet rows) throws SQLException {
        Movement m = new Movement();
        m.id = rows.getLong("id"); m.productId = rows.getLong("product_id"); m.sku = rows.getString("sku"); m.productName = rows.getString("name");
        m.delta = rows.getInt("delta"); m.balance = rows.getInt("balance"); m.reason = rows.getString("reason"); m.actor = rows.getString("actor");
        m.requestId = rows.getString("request_id"); m.createdAt = rows.getTimestamp("created_at", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString();
        return m;
    }
}
