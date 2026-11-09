# Bundled libraries

- **Gson 2.13.2**, Google and contributors — Apache License 2.0.
  Project/source: https://github.com/google/gson/tree/gson-parent-2.13.2
  Artifact: https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/
- **MariaDB Connector/J 3.5.11**, MariaDB and contributors — LGPL 2.1 or later.
  Project/source: https://github.com/mariadb-corporation/mariadb-connector-j
  Exact corresponding source is included in the server package's `sources` folder.
  The JDBC library remains a separate replaceable JAR in `lib`; you may replace it
  with a compatible modified version. No library sources were modified.

Full license texts are in `licenses`. The application does not bundle the Java
runtime. Docker images are obtained separately from their upstream publishers.
