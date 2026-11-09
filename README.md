# Stockroom — Java Inventory Management

A native **Java Swing desktop client** connects over HTTP/HTTPS to a separate
**Java backend server**, which stores products and an audit trail in **MariaDB**.
Java 11 or newer is required. The code uses readable names and comments explaining
transactions, threading, and safe retries.

## Features

- Create and edit products: SKU, name, category, storage location, price, reorder threshold.
- Track opening stock, receipts, issues, corrections, and remaining balances.
- Search and sort inventory; highlight/filter products at or below reorder level.
- Review timestamped stock history and export the displayed inventory to CSV.
- Archive zero-stock products while retaining their history.
- Prevent negative stock and conflicting edits from concurrent clients.
- Retry stock adjustments without applying the same change twice.
- Keep the UI responsive while the backend handles requests.

## Run everything locally on Windows

1. Start **Docker Desktop**. A JDK 11+ must be on PATH (`java`, `javac`, `jar`).
2. Double-click **run-local.bat** in this folder.
3. The launcher generates local configuration if needed, builds missing packages,
   starts MariaDB and the Java server, then opens the desktop client.
4. Use server URL **http://localhost:8080**. Copy the `api.token` value from
   **server.properties** into the client's API token field, then click **Connect**.
5. Choose **New product**. Use **Adjust stock** for receipts or issues and supply
   a reason. Select a row to see its movement history below.

Manual equivalents:

```powershell
./setup-local.ps1
docker compose up -d db
./build.ps1
./run-server.bat   # leave this terminal open
./run-client.bat   # run in another terminal
```

The setup script generates different random secrets on each installation. It
does not overwrite existing configuration. `.env` and `server.properties` are
excluded from Git and from the distributable ZIPs. Never send them to desktop users.

## Build and distribute

```powershell
./build.ps1 -Test
./integration-test.ps1 -Ui
```

`build.ps1` downloads two pinned dependencies when absent, verifies their SHA-256
hashes, compiles for Java 11, and creates:

- **dist/Stockroom-Desktop.zip** — send this to desktop users. Extract it and run
  **Start-Client.bat**; install a Java 11+ runtime first.
- **dist/Stockroom-Server.zip** — deploy this on the server. Extract it, copy
  `server.properties.example` to `server.properties`, configure MariaDB credentials
  and a random API token, then run **Start-Server.bat**.

Keep each extracted folder intact: the JAR needs the adjacent `lib` folder.
The desktop package includes Gson, but no MariaDB JDBC driver or DB credentials.

On Linux, the server starts with:

```bash
java -cp 'inventory-server.jar:lib/*' inventory.Server /etc/stockroom/server.properties
```

The database and its `inventory` schema/user must exist before starting the server;
the backend creates its InnoDB tables automatically. The Compose database service
provisions the schema and application user for local use.

## Deploy the Java server and MariaDB together

After generating `.env`, run:

```powershell
docker compose up -d --build
```

This uses the provided multi-stage Dockerfile to build the Java backend and runs
it beside MariaDB. The database uses a persistent named volume. Compose publishes
both ports on **127.0.0.1**, so they are local to the server host.

For clients on other computers, put an HTTPS reverse proxy in front of
`127.0.0.1:8080` and enter its HTTPS URL in the desktop client. Example Caddy config:

```caddyfile
inventory.your-domain.example {
    reverse_proxy 127.0.0.1:8080
}
```

Replace the example hostname with your real domain and configure DNS before
starting the proxy. The desktop accepts HTTP for localhost; other HTTP addresses
require explicitly enabling the trusted-LAN checkbox. Keep MariaDB private.
This application uses one shared API token and one configured operator identity
for a trusted team. Separate user accounts, roles, and per-person audit identities
are not implemented. HTTPS and host access controls belong in the server deployment.

## Where data and credentials live

- **Products/history:** MariaDB tables `products` and `movements`; the Compose
  named volume ending in `inventory-data` persists across container restarts.
- **Server settings:** local `server.properties` or `INVENTORY_*` environment variables.
- **Database container passwords:** local `.env`, consumed by Docker Compose.
- **Desktop:** saves only the server URL in Java Preferences. The API token is
  held in memory during the session; it is cleared from the password field after
  connecting. The app never saves the token to client preferences or files.

Do not run `docker compose down -v` unless you intend to erase the database.
Closing the desktop leaves the server and database running. `docker compose stop db`
stops the local database; stop a foreground Java backend with Ctrl+C. If using the
background launcher, use Windows Task Manager to end the Java process whose command
line contains `inventory.Server`. For the all-container deployment use
`docker compose stop`.

## API

All routes except health require `Authorization: Bearer <api.token>`.
Writes require `Content-Type: application/json`.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Check backend and database |
| GET | `/api/products?q=widget&lowStock=true` | Search active products; max 2,000 rows |
| GET | `/api/products/{id}` | Read a product, including an archived product |
| POST | `/api/products` | Create product and opening movement |
| PUT | `/api/products/{id}` | Edit metadata using its current `version` |
| POST | `/api/products/{id}/adjust` | Apply `{delta, reason, requestId}` atomically |
| POST | `/api/products/{id}/archive` | Archive zero-stock product using `{version}` |
| GET | `/api/movements?productId=1` | Last 500 stock movements; omit ID for all products |

Example stock change body:

```json
{"delta": 12, "reason": "Supplier receipt PO-1001", "requestId": "651780bb-47d2-41b8-8d65-0ba1b7c5ef90"}
```

Generate a **new UUID for each different operation**. Reuse the same body and UUID
when retrying an operation whose response was lost. The server rejects a UUID
reused for a different operation. Quantities are whole units, up to 1,000,000 per
product. Prices have up to two decimals; currency conversion is not implemented.
The stock value shown in the client covers the currently displayed search results.

## Source map and verification

- `Models.java`: JSON models and validation.
- `Database.java`: prepared SQL, row locking, transactions, version checks, history.
- `Server.java`: bounded HTTP worker pool, authentication, routes, JSON/error handling.
- `ApiClient.java`: desktop HTTP transport and connection policy.
- `DesktopApp.java`: Swing forms, tables, background requests, CSV export.
- `InventoryTest.java`: validation, real HTTP/MariaDB integration, concurrency, UI checks.

See **VERIFICATION.md** and **build/test-results.txt** for results. The integration
script creates a disposable isolated MariaDB container and removes it afterward;
it does not populate or change your real inventory database.

Libraries: [Gson](https://github.com/google/gson) (Apache-2.0) and
[MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)
(LGPL-2.1-or-later). JARs retain their upstream metadata/licenses.
