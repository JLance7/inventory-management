# Verification — 2026-11-08

- Compiled all application and test sources with the installed JDK 11 using
  `javac --release 11`; packaged separate desktop and server JARs and ZIPs.
- Verified both runtime dependency downloads against pinned SHA-256 hashes.
- Passed **45 assertions** with a real isolated MariaDB 11.4 container and live
  Java HTTP server. No in-memory/fake database was used for integration tests.
- Tested authentication, request validation, malformed/oversized JSON, duplicate
  SKUs, parameterized search, transactional adjustments, idempotent retries,
  stale metadata edits, rejected overselling, archive rules, and audit integrity.
- Submitted 20 concurrent issue requests against 10 units: exactly 10 succeeded,
  10 returned a conflict, final stock was zero, and all successful changes had
  one audit entry.
- Started a second backend instance against the same MariaDB database and verified
  it could read persisted balances.
- Ran Swing client checks against the live API: connection, inventory display,
  enabled action buttons, selection-driven stock history. Rendered and inspected
  `build/desktop-preview.png`.
- Test MariaDB containers were removed after the runs. Test records are not part
  of the real inventory database.

The remote HTTPS reverse proxy and Linux/container server deployment are provided
as deployment options; they have not been exercised on a remote server. A separate
remote server, domain, TLS setup, and multi-user permissions were not requested or
provisioned.
