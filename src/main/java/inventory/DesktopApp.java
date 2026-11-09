package inventory;

import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.table.*;
import static inventory.Models.*;

/** Native desktop client. Server requests run in background workers so a slow
 * network never freezes typing, scrolling, or repainting the application. */
public final class DesktopApp extends JFrame {
    private final JTextField address = new JTextField("http://localhost:8080", 26);
    private final JPasswordField token = new JPasswordField(24);
    private final JCheckBox insecure = new JCheckBox("Allow HTTP on trusted LAN");
    private final JTextField search = new JTextField(28);
    private final JCheckBox lowStock = new JCheckBox("Low stock only");
    private final JLabel status = new JLabel("Connect to your inventory server to begin.");
    private final JLabel summary = new JLabel("INVENTORY  /  No server connected");
    private final DefaultTableModel tableModel = new DefaultTableModel(
        new Object[]{"SKU", "Product", "Category", "Location", "In stock", "Reorder at", "Unit price", "Stock value"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) {
            return column == 4 || column == 5 ? Integer.class : column >= 6 ? BigDecimal.class : String.class;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final DefaultTableModel historyModel = new DefaultTableModel(new Object[]{"UTC time", "SKU", "Change", "Balance", "Reason", "Operator"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable history = new JTable(historyModel);
    private final List<JButton> actions = new ArrayList<>();
    private final JButton connect = new JButton("Connect");
    private final Preferences preferences = Preferences.userNodeForPackage(DesktopApp.class);
    private List<Product> products = new ArrayList<>();
    private ApiClient api;
    private boolean busy;
    private long historyGeneration;

    public DesktopApp() {
        super("Stockroom — Inventory Management");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1340, 860); setMinimumSize(new Dimension(1050, 700)); setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBorder(BorderFactory.createEmptyBorder(24, 26, 18, 26)); setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(0, 14));
        JLabel title = new JLabel("STOCKROOM"); title.setFont(new Font("Segoe UI", Font.BOLD, 30));
        title.setForeground(new Color(26, 91, 128));
        JPanel brand = new JPanel(new BorderLayout()); brand.add(title, BorderLayout.WEST);
        brand.add(new JLabel("Desktop client  →  Java server  →  MariaDB"), BorderLayout.EAST);
        header.add(brand, BorderLayout.NORTH);
        JPanel connection = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints(); g.insets = new Insets(4, 5, 4, 5); g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; connection.add(new JLabel("Server URL"), g);
        g.gridx = 1; g.weightx = 1; connection.add(address, g);
        g.gridx = 2; g.weightx = 0; connection.add(new JLabel("API token"), g);
        g.gridx = 3; g.weightx = 1; connection.add(token, g);
        g.gridx = 4; g.weightx = 0; connection.add(connect, g);
        g.gridx = 1; g.gridy = 1; g.gridwidth = 4; connection.add(insecure, g);
        address.setText(preferences.get("serverUrl", "http://localhost:8080"));
        header.add(connection, BorderLayout.CENTER); root.add(header, BorderLayout.NORTH);

        JPanel inventory = new JPanel(new BorderLayout(0, 12));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        addAction(toolbar, "Refresh", this::refresh);
        addAction(toolbar, "New product", () -> edit(null));
        addAction(toolbar, "Edit", () -> withSelection(this::edit));
        addAction(toolbar, "Adjust stock", () -> withSelection(this::adjust));
        addAction(toolbar, "Archive", () -> withSelection(this::archive));
        addAction(toolbar, "Export CSV", this::export);
        // Wrap the actions separately to keep every button visible at narrow widths.
        JPanel inventoryHeader = new JPanel(new BorderLayout(0, 10));
        summary.setFont(new Font("Segoe UI", Font.BOLD, 16));
        inventoryHeader.add(summary, BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridLayout(2, 1, 0, 9));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        filters.add(new JLabel("Search")); filters.add(search); filters.add(lowStock);
        rows.add(filters); rows.add(toolbar); inventoryHeader.add(rows, BorderLayout.CENTER);
        inventory.add(inventoryHeader, BorderLayout.NORTH);
        configure(table); configure(history);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new StockRenderer());
        table.setDefaultRenderer(Integer.class, new StockRenderer());
        table.setDefaultRenderer(BigDecimal.class, new StockRenderer());
        JScrollPane inventoryScroll = new JScrollPane(table);
        inventoryScroll.setColumnHeaderView(table.getTableHeader());
        inventory.add(inventoryScroll, BorderLayout.CENTER);
        JPanel audit = new JPanel(new BorderLayout(0, 8));
        audit.add(new JLabel("STOCK HISTORY  /  Select a product to view its last 500 movements"), BorderLayout.NORTH);
        JScrollPane historyScroll = new JScrollPane(history);
        historyScroll.setColumnHeaderView(history.getTableHeader());
        audit.add(historyScroll, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inventory, audit);
        split.setResizeWeight(.7); split.setDividerLocation(430); split.setBorder(null);
        root.add(split, BorderLayout.CENTER); root.add(status, BorderLayout.SOUTH);
        status.setForeground(new Color(70, 87, 104));
        connect.addActionListener(event -> connect());
        search.addActionListener(event -> refresh()); lowStock.addActionListener(event -> refresh());
        table.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadHistory(); });
        table.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) withSelection(DesktopApp.this::edit); } });
        addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { if (api != null) api.clearCredentials(); token.setText(""); } });
        enableActions();
    }

    private static void configure(JTable table) {
        table.setRowHeight(30); table.setShowVerticalLines(false); table.setFillsViewportHeight(true);
        table.setIntercellSpacing(new Dimension(10, 1)); table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));
    }
    private void addAction(JPanel panel, String label, Runnable action) {
        JButton button = new JButton(label); button.addActionListener(event -> action.run()); actions.add(button); panel.add(button);
    }
    private void enableActions() {
        for (JButton button : actions) button.setEnabled(api != null && !busy);
        connect.setEnabled(!busy); search.setEnabled(api != null && !busy); lowStock.setEnabled(api != null && !busy);
    }
    private void connect() {
        if (api != null) { api.clearCredentials(); api = null; products.clear(); tableModel.setRowCount(0); historyModel.setRowCount(0); }
        char[] secret = token.getPassword();
        final ApiClient candidate;
        try { candidate = new ApiClient(address.getText(), secret, insecure.isSelected()); }
        catch (Exception error) { showError(error); enableActions(); return; }
        finally { Arrays.fill(secret, '\0'); token.setText(""); }
        task("Connecting…", () -> {
            try { return candidate.products("", false); }
            catch (Exception error) { candidate.clearCredentials(); throw error; }
        }, result -> {
            api = candidate; preferences.put("serverUrl", address.getText().strip());
            search.setText(""); lowStock.setSelected(false); display(result);
        });
    }
    private void refresh() {
        if (api == null || busy) return;
        final String query = search.getText(); final boolean low = lowStock.isSelected();
        task("Loading inventory…", () -> api.products(query, low), this::display);
    }
    private void display(Product[] values) {
        historyGeneration++; historyModel.setRowCount(0); table.clearSelection(); products = Arrays.asList(values);
        tableModel.setRowCount(0); int low = 0; BigDecimal value = BigDecimal.ZERO;
        for (Product p : products) {
            BigDecimal stockValue = p.unitPrice.multiply(BigDecimal.valueOf(p.quantity)); value = value.add(stockValue);
            if (p.quantity <= p.reorderLevel) low++;
            tableModel.addRow(new Object[]{p.sku, p.name, p.category, p.location, p.quantity, p.reorderLevel, p.unitPrice, stockValue});
        }
        summary.setText(values.length + " PRODUCTS SHOWN     /     " + low + " LOW STOCK     /     VALUE " + value.toPlainString());
        status.setText("Connected · " + address.getText() + " · Search matches SKU, name, category, or location · Up to 2,000 results");
    }
    private Product selection() {
        int row = table.getSelectedRow();
        return row < 0 ? null : products.get(table.convertRowIndexToModel(row));
    }
    private void withSelection(Consumer<Product> action) {
        Product product = selection();
        if (product == null) JOptionPane.showMessageDialog(this, "Select a product first.");
        else action.accept(product);
    }
    private void edit(Product product) {
        JTextField sku = new JTextField(product == null ? "" : product.sku);
        JTextField name = new JTextField(product == null ? "" : product.name);
        JTextField category = new JTextField(product == null ? "" : product.category);
        JTextField location = new JTextField(product == null ? "" : product.location);
        JTextField price = new JTextField(product == null ? "0.00" : product.unitPrice.toPlainString());
        JSpinner quantity = new JSpinner(new SpinnerNumberModel(product == null ? 0 : product.quantity, 0, 1_000_000, 1));
        quantity.setEnabled(product == null);
        JSpinner reorder = new JSpinner(new SpinnerNumberModel(product == null ? 5 : product.reorderLevel, 0, 1_000_000, 1));
        JPanel form = form(new String[]{"SKU", "Name", "Category", "Location", "Unit price", product == null ? "Opening stock" : "Stock (use Adjust stock)", "Reorder at"},
            new JComponent[]{sku, name, category, location, price, quantity, reorder});
        while (JOptionPane.showConfirmDialog(this, form, product == null ? "New product" : "Edit " + product.sku,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                quantity.commitEdit(); reorder.commitEdit();
                ProductInput input = new ProductInput();
                input.sku = sku.getText(); input.name = name.getText(); input.category = category.getText(); input.location = location.getText();
                input.unitPrice = new BigDecimal(price.getText().strip()); input.initialQuantity = (int) quantity.getValue();
                input.reorderLevel = (int) reorder.getValue(); input.version = product == null ? 0 : product.version;
                input.validate();
                task("Saving product…", () -> api.save(product, input), saved -> refresh()); return;
            } catch (Exception error) { showError(error); }
        }
    }
    private void adjust(Product product) {
        JSpinner delta = new JSpinner(new SpinnerNumberModel(1, -1_000_000, 1_000_000, 1));
        JTextField reason = new JTextField();
        JPanel form = form(new String[]{"Current balance", "Change (+ receive / − issue)", "Reason"},
            new JComponent[]{new JLabel(Integer.toString(product.quantity)), delta, reason});
        while (JOptionPane.showConfirmDialog(this, form, "Adjust " + product.sku, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                delta.commitEdit();
                Adjustment input = new Adjustment(); input.delta = (int) delta.getValue(); input.reason = reason.getText();
                input.requestId = UUID.randomUUID().toString(); input.validate();
                sendAdjustment(product.id, input); return;
            } catch (Exception error) { showError(error); }
        }
    }
    private void sendAdjustment(long id, Adjustment input) {
        // Preserve this request ID on retry: a lost HTTP response must not double
        // receive/issue stock. The server returns the original movement on replay.
        task("Recording stock movement…", () -> api.adjust(id, input), movement -> refresh(), error -> {
            int choice = JOptionPane.showConfirmDialog(this, error.getMessage()
                + "\nRetry this same stock change safely? Its request ID will be preserved.", "Stock change could not be confirmed", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) sendAdjustment(id, input);
            else refresh();
        });
    }
    private void archive(Product product) {
        if (product.quantity != 0) { JOptionPane.showMessageDialog(this, "Issue or correct the remaining stock before archiving."); return; }
        if (JOptionPane.showConfirmDialog(this, "Archive " + product.sku + "? Its audit history will be preserved.", "Archive product", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            task("Archiving…", () -> { api.archive(product); return true; }, ignored -> refresh());
    }
    private void loadHistory() {
        Product product = selection(); final long generation = ++historyGeneration;
        if (product == null || api == null) return;
        final ApiClient client = api;
        new SwingWorker<Movement[], Void>() {
            @Override protected Movement[] doInBackground() throws Exception { return client.history(product.id); }
            @Override protected void done() {
                if (generation != historyGeneration || client != api) return;
                try {
                    Movement[] entries = get(); historyModel.setRowCount(0);
                    for (Movement m : entries) historyModel.addRow(new Object[]{m.createdAt, m.sku, m.delta > 0 ? "+" + m.delta : m.delta, m.balance, m.reason, m.actor});
                } catch (Exception error) { status.setText("Could not load history: " + cause(error).getMessage()); }
            }
        }.execute();
    }
    private void export() {
        JFileChooser picker = new JFileChooser(); picker.setSelectedFile(new java.io.File("inventory.csv"));
        if (picker.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = picker.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        List<Product> snapshot = new ArrayList<>(products);
        task("Exporting…", () -> {
            StringBuilder csv = new StringBuilder("SKU,Name,Category,Location,Quantity,Reorder level,Unit price\r\n");
            for (Product p : snapshot) csv.append(cell(p.sku)).append(',').append(cell(p.name)).append(',').append(cell(p.category)).append(',')
                .append(cell(p.location)).append(',').append(p.quantity).append(',').append(p.reorderLevel).append(',').append(p.unitPrice.toPlainString()).append("\r\n");
            Files.writeString(path, csv, StandardCharsets.UTF_8); return path;
        }, saved -> status.setText("Exported displayed products to " + saved));
    }
    static String cell(String value) {
        String safe = value.matches("^[=+@-].*") ? "'" + value : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
    private static JPanel form(String[] labels, JComponent[] fields) {
        JPanel panel = new JPanel(new GridLayout(labels.length, 2, 12, 12));
        panel.setPreferredSize(new Dimension(540, labels.length * 42));
        for (int i = 0; i < labels.length; i++) { panel.add(new JLabel(labels[i])); panel.add(fields[i]); }
        return panel;
    }
    private <T> void task(String message, Callable<T> background, Consumer<T> success) { task(message, background, success, this::showError); }
    private <T> void task(String message, Callable<T> background, Consumer<T> success, Consumer<Throwable> failure) {
        busy = true; enableActions(); status.setText(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return background.call(); }
            @Override protected void done() {
                busy = false;
                try { success.accept(get()); }
                catch (Exception error) { failure.accept(cause(error)); }
                finally { enableActions(); }
            }
        }.execute();
    }
    private static Throwable cause(Throwable error) {
        return error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error;
    }
    private void showError(Throwable error) {
        status.setText("Request failed: " + error.getMessage());
        JOptionPane.showMessageDialog(this, error.getMessage(), "Inventory", JOptionPane.ERROR_MESSAGE);
    }
    private final class StockRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focused, int row, int column) {
            Component component = super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            Product product = products.get(owner.convertRowIndexToModel(row));
            if (!selected) {
                component.setBackground(product.quantity <= product.reorderLevel ? new Color(255, 243, 218) : row % 2 == 0 ? Color.WHITE : new Color(244, 247, 250));
                component.setForeground(new Color(30, 49, 66));
            }
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setHorizontalAlignment(column >= 4 ? RIGHT : LEFT);
            return component;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
            UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 13));
            new DesktopApp().setVisible(true);
        });
    }
}
