# Mirage — Configuration & Runtime

> Back to [README.md](README.md) · [中文版](../CONFIG.md)

## File Locations (Standard Directories)

| Deployment | Data directory | Config / DB / Logs |
|---|---|---|
| Frontier standalone (`java -jar mirage.jar`) | The working directory at launch | `./mirage.properties`, `./data/`, `./logs/` |
| Bukkit / Spigot / Paper (NMS) | `plugins/Mirage/` | `plugins/Mirage/mirage.properties`, `plugins/Mirage/data/`, `plugins/Mirage/logs/` |
| Leaf | `plugins/Mirage/` | Same as above |
| Folia | `plugins/Mirage/` | Same as above |
| Velocity | `plugins/mirage/` | `plugins/mirage/mirage.properties`, `plugins/mirage/data/`, `plugins/mirage/logs/` |
| Fabric (skeleton) | `config/mirage/` | Same structure |

The plugin prints the actual **data folder** absolute path at startup, so it is easy to locate.
If the configuration file does not exist on first launch, a **fully commented `mirage.properties`
is generated automatically**. All relative paths in the configuration (`db.path`, `log.file`) are
resolved against that data directory, so the whole deployment can be relocated as one unit.

## Configuration (mirage.properties)

The auto-generated configuration includes every tunable option and inline comment. Changes take
effect **after a restart**.

| Key | Default | Description |
|---|---|---|
| `server.host` | `0.0.0.0` | Bind address. `0.0.0.0` = all interfaces, `127.0.0.1` = local only |
| `server.port` | `3306` | MySQL listen port |
| `server.name` | `Mirage` | Server name (visible to clients) |
| `server.version` | `8.4.0-mirage` | Returned by `SELECT VERSION()` |
| `server.backlog` | `512` | TCP backlog size |
| `server.maxConn` | `256` | Max concurrent connections (thread pool cap, minimum 8) |
| `db.path` | `./data/h2db` | H2 2.x library path (Java 11+) |
| `db.path.legacy` | `./data/h2db-j8` | H2 1.4.200 library path (Java 8) |
| `db.user` / `db.password` | `sa` / empty | **H2 embedded DB credentials (internal only)**; not client login credentials |
| `db.autoServer` | `true` | Allow multiple processes to share the DB file; on DNS-less environments enabling this can add 20-30s to first open; set to `false` for single-process |
| `log.queries` | `false` | Log every SQL statement (performance killer; enable only for debugging) |
| `log.console` | `true` | Log to console |
| `log.file` | `./logs/mirage.log` | Log file path |
| `log.level` | `INFO` | Minimum log level: `DEBUG` / `INFO` / `WARN` / `ERROR` |
| `auth.strict` | `true` | Strict client password check; `false` = lenient mode (unknown users allowed; **debug only**) |
| `auth.username` | `root` | **Client login account name** (single account) |
| `auth.password` | `root` | **Client login password**; leave blank = password-less (local debugging only) |

> **Login credential note**: Clients connect to Mirage using the single account configured via
> `auth.username` / `auth.password` (MySQL wire-protocol authentication, supporting both
> `mysql_native_password` and `caching_sha2_password`). `db.user` / `db.password` are only the
> credentials Mirage itself uses to access the H2 file DB internally; they are unrelated to the client.
> The default account is `root / root` — **change it before exposing to the internet**, and tighten
> `server.host` to `127.0.0.1` or an internal address.
>
> **Single account only**: For security, Mirage no longer supports multiple accounts, and
> `CREATE USER` / `DROP USER` no longer take effect (only return OK to let clients continue).
> The old `user.*` / `users=a:b,c:d` formats are disabled; only kept as comments.

## Shutdown Sequence (Data Safety)

The plugin / program is the **last to exit**; the shutdown order is fixed:

1. Close `ServerSocket` — stop accepting new connections
2. **Actively disconnect all client connections** — worker threads exit immediately, no new SQL is produced
3. Wait for the connection thread pool to converge (10s cap, force-terminated on timeout)
4. Close all session H2 connections (uncommitted transactions roll back; committed data is retained)
5. `CHECKPOINT` flush + close the main H2 connection, releasing the library lock
6. Flush and close the log file

Standalone runs trigger the same sequence via a JVM shutdown hook; MC plugins call it from
`onDisable` / `onProxyShutdown`. Verified: keeping clients connected during shutdown, data is
intact after restart.
