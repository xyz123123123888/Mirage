# Mirage — Live Verification Record

> Back to [README.md](README.md) · [中文版](../TESTING.md)

## Delivery Status

**All three plugins (NMS / Folia / Velocity) have been verified on real servers.** Velocity
additionally covers 4 versions: 1.0.10 / 3.1.0 / 4.0.0 / 4.3.1 (see [COMPATIBILITY.md](COMPATIBILITY.md)).

### NMS + Paper (Paper 1.21.4 build 232 + Java 25)

```
[PluginInitializerManager] Initialized 1 plugin
 - Mirage (8.4.0-mirage)
[01:26:03 INFO]: [Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
[01:26:11 INFO]: Done (141.347s)! For help, type "help"
```

### Velocity Proxy (Velocity 4.3.1 build 45 + Java 25)

```
[02:12:06 INFO]: Loaded plugin mirage 8.4.0-mirage by Mirage
[02:12:18 INFO] [mirage]: Mirage MySQL proxy ready on 0.0.0.0:3306
[02:12:18 INFO]: Listening on /[0:0:0:0:0:0:0:0]:25565
[02:12:18 INFO]: Done (20.18s)!
```

### Leaf Server (Paper 1.21.4 build 232 + Java 25, with mirage-nms.jar)

```
[Mirage] Loading server plugin Mirage v8.4.0-mirage
[Mirage] Mirage loading (STARTUP priority)...
[Mirage] Mirage enabling, starting MySQL proxy...
[Mirage] Mirage data folder: ...\plugins\Mirage
[Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
Done (99.115s)! For help, type "help"
```

> Note: `mirage-nms.jar` is a single jar with Leaf optimizations built in; no separate
> `mirage-leaf.jar` is needed.

### Graceful Shutdown Verification (7 environments)

Shutdown sequence (disconnect clients → wait for worker threads → close session connections →
CHECKPOINT flush → close H2 → close log) has been verified on real servers. **With clients kept
connected during shutdown, all committed data survives a restart with zero loss**:

| Platform | Client rows written | Post-restart check | Result |
|---|---|---|---|
| NMS (Paper 1.21.4) | 11 rows | `rows=11 maxId=11` | ✅ Zero loss |
| Velocity 1.0.10 (`--add-opens` required) | — | Startup/shutdown OK (data persistence not verified) | ✅ |
| Velocity 3.1.0 | — | Startup/shutdown OK (data persistence not verified) | ✅ |
| Velocity 4.0.0 | — | Startup/shutdown OK (data persistence not verified) | ✅ |
| Velocity 4.3.1 | 9 rows | `rows=9 maxId=9` | ✅ Zero loss |
| Leaf (Paper 1.21.4) | 3 rows | `rows=3 maxId=3` | ✅ Zero loss |

All three endpoints log (mixed Chinese/English output shown as-is; inline comments explain the Chinese lines):
```
Disabling Mirage v8.4.0-mirage
Mirage disabling: closing connections and flushing database...
[Db] 数据库已安全关闭              # "Database safely closed"
[HH:mm:ss] INFO  Mirage 已安全停止  # "Mirage stopped safely"
Mirage stopped safely (data flushed).
```

Standard directories verified too: `plugins/Mirage/mirage.properties`, `plugins/Mirage/data/h2db`,
`plugins/Mirage/logs/mirage.log` (or `plugins/mirage/` for Velocity). A fully commented
configuration file is auto-generated on first launch.

> Fix record: `Console` used to write directly to `System.out`, triggering the Paper warning
> `Nag author(s): ... usage of System.out/err.print`. All output now goes through `LogSink`;
> once the host logger takes over, the warning disappears and output no longer carries the
> `[STDOUT]` prefix.

### Verification Coverage (all four platforms: NMS / Leaf / Folia / Velocity)

- Port 3306 is listened on inside the host process; JDBC connects, creates tables, runs DML
- All MySQL-compatible functions verified: REVERSE / SUBSTRING_INDEX / FIELD / DATE / TIME / DATE_FORMAT
- Server logs show no ERROR, no Nag warnings (core logs routed through LogSink into the host logger)
- Leaf-only deployment shows 0 ERROR lines in the log
- **Velocity plugin metadata must be JSON** (`velocity-plugin.json`); YAML causes
  `Expected BEGIN_OBJECT but was STRING`
- **Both frontier fat jars verified standalone**: `java -jar mirage.jar` (Java 11+) and
  `java -jar mirage-java8.jar` (Java 8) both listen on 3306 and provide full MySQL service

- **All four server/proxy jars (NMS / Leaf / Folia / Velocity) are built**; each contains
  `mirage.core` + `org.h2` + plugin classes + metadata files. Single-file deployment.
- **All built against real APIs** (downloaded from repo.papermc.io):
  - Paper API 1.21.4-R0.1-20250925.065901-231 (NMS / Leaf)
  - Velocity API 4.3.1 + slf4j-api 2.0.9 + guice 5.1.0 (Velocity)
- Plugin class bytecode: **all major 55 (Java 11)**, matching H2 2.5.252's minimum.
- Build note: Paper API 1.21.4 and Velocity API 4.3.1 dependency jars themselves contain Java 21+/25
  bytecode, but only for compile time. Our artifacts are all compiled with JDK 25 `--release 11`,
  so runtime only requires Java 11+.
- **H2 ALIAS must use `FOR "mirage.core.MySqlFunctions.xxx"` to reference pre-compiled methods**;
  `AS $$Java source$$` makes H2 dynamically compile class 69 on Java 25, triggering
  `Unsupported class file major version 69`.
- **Fabric ships as a source skeleton only**: Fabric mods must be built with Gradle + Loom
  and require downloading Minecraft / yarn mappings / Fabric Loader. The build machine has no
  network access to those repositories, so no runnable jar can be produced.
  See [../mc/server/fabric/README.md](../mc/server/fabric/README.md).

## Verification Matrix

| Combination | Result |
|---|---|
| Paper 1.21.4 + Java 25 + NMS | ✅ Startup / shutdown / data persistence |
| Paper 1.21.4 + Java 25 + Leaf | ✅ Startup / shutdown / data persistence |
| Velocity 1.0.10 + Java 25 (`--add-opens` required) | ✅ Startup / shutdown (data persistence not verified) |
| Velocity 3.1.0 + Java 25 | ✅ Startup / shutdown (data persistence not verified) |
| Velocity 4.0.0 + Java 25 | ✅ Startup / shutdown (data persistence not verified) |
| Velocity 4.3.1 + Java 25 | ✅ Startup / shutdown / data persistence |
| **Real Leaf 1.21.4 + Java 25 + mirage-nms.jar (Leaf built in)** | ✅ Startup / JDBC / functions / shutdown / **22 rows zero loss** |
| **Folia 1.19.4 + Java 17 + Folia plugin** | ✅ Startup / JDBC / functions / shutdown / **18 rows zero loss** |
| **Folia 26.3 + Java 25 + Folia plugin** | ✅ Startup / JDBC / functions / shutdown / **22 rows zero loss** |
| **Paper 1.16.5 + Java 11.0.2 + NMS** | ✅ Startup / JDBC / functions / shutdown / **15 rows zero loss** |
| **mirage.jar + Java 16.0.2 (standalone)** | ✅ Listening correctly |

> Note: Paper 1.16.5 officially recommends Java 16; launching with Java 11 requires
> `-DPaper.IgnoreJavaVersion=true`. Paper 1.16.4 and earlier officially recommend Java 11 and
> do not need the flag. The Mirage plugin itself works fine on Java 11.
