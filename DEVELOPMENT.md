# User requirements and implementation context

Build a Java inventory management/tracking system locally in
`D:\joshu\Documents\programming\github\inventory`. A Java desktop frontend
connects to a Java backend on a server; the backend persists inventory in MariaDB.
Do not publish to GitHub unless asked. Keep code readable with useful comments.

The first implementation is Stockroom: Java 11 Swing + JDK HttpServer/HttpClient,
Gson JSON, JDBC with MariaDB Connector/J. It includes product management, stock
adjustments, low-stock filters, audit history, CSV export, and separate desktop/
server ZIPs. It uses one shared API token, not a multi-user login system.

Preserve transactional stock changes, optimistic metadata versions, request-ID
idempotency, and background desktop requests when extending the app. Never place
database credentials in the desktop build. Test changes against real MariaDB with
`integration-test.ps1 -Ui` when they affect persistence or client/server behavior.
