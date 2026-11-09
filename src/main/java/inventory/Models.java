package inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** Shared wire models. The desktop never receives database credentials. */
public final class Models {
    private Models() {}

    public static final class Product {
        public long id;
        public String sku, name, category, location;
        public BigDecimal unitPrice;
        public int quantity, reorderLevel, version;
        public boolean archived;
    }

    public static final class ProductInput {
        public String sku, name, category = "", location = "";
        public BigDecimal unitPrice = BigDecimal.ZERO;
        public int initialQuantity, reorderLevel, version;

        public void validate() {
            sku = text(sku, "SKU", 64, false).toUpperCase(java.util.Locale.ROOT);
            name = text(name, "Name", 200, false);
            category = text(category, "Category", 100, true);
            location = text(location, "Location", 100, true);
            require(unitPrice != null && unitPrice.signum() >= 0
                && unitPrice.compareTo(new BigDecimal("9999999999.99")) <= 0
                && unitPrice.stripTrailingZeros().scale() <= 2,
                "Price must be nonnegative, at most 9999999999.99, with up to two decimals.");
            require(initialQuantity >= 0 && initialQuantity <= 1_000_000, "Opening quantity must be 0–1,000,000.");
            require(reorderLevel >= 0 && reorderLevel <= 1_000_000, "Reorder level must be 0–1,000,000.");
            require(version >= 0, "Invalid version.");
        }
    }

    public static final class Adjustment {
        public int delta;
        public String reason, requestId;
        public void validate() {
            require(delta != 0 && delta >= -1_000_000 && delta <= 1_000_000,
                "Quantity change must be nonzero and between -1,000,000 and 1,000,000.");
            reason = text(reason, "Reason", 250, false);
            try { requestId = UUID.fromString(requestId).toString(); }
            catch (Exception error) { throw new Problem(400, "A valid requestId UUID is required."); }
        }
    }

    public static final class Movement {
        public long id, productId;
        public String sku, productName, reason, actor, createdAt, requestId;
        public int delta, balance;
    }

    public static final class Problem extends RuntimeException {
        public final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }

    static String text(String value, String label, int max, boolean optional) {
        String result = value == null ? "" : value.strip();
        require((optional || !result.isEmpty()) && result.length() <= max
            && result.chars().noneMatch(Character::isISOControl), label + " is missing, too long, or contains control characters.");
        return result;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new Problem(400, message);
    }
}
