# Mirage — Compatibility

> Back to [README.md](README.md) · [中文版](../COMPATIBILITY.md)

## Velocity Compatibility

`mirage-velocity.jar` uses **the most stable layer of the Velocity API**, so it is theoretically
compatible with all Velocity versions (1.0 ~ 4.x).

The plugin only uses the following APIs:

| API | Purpose | 1.x | 2.x | 3.x | 4.x |
|---|---|---|---|---|---|
| `@Plugin(id, name, version, description, authors)` | Plugin metadata | ✅ | ✅ | ✅ | ✅ |
| `@Subscribe` | Event listener marker | ✅ | ✅ | ✅ | ✅ |
| `ProxyInitializeEvent` / `ProxyShutdownEvent` | Lifecycle events | ✅ | ✅ | ✅ | ✅ |
| `@DataDirectory Path` | Data directory injection | ✅ | ✅ | ✅ | ✅ |
| `ProxyServer` | Proxy instance injection | ✅ | ✅ | ✅ | ✅ |
| `Logger` (slf4j) | Logging | ✅ | ✅ | ✅ | ✅ |

No scheduler, command API, Adventure text, or configuration loading is used — those are the
parts that change most between Velocity versions.

### Tested Versions

| Velocity | Java | Result | Notes |
|---|---|---|---|
| **1.0.10** | 25 | ✅ | Requires `--add-opens java.base/java.lang=ALL-UNNAMED` (see below) |
| **3.1.0** | 25 | ✅ | Works out of the box |
| **4.0.0** | 25 | ✅ | Works out of the box |
| **4.3.1** | 25 | ✅ | Works out of the box |

### Required JVM Flags for Old Versions (e.g. 1.0.10)

Velocity 1.0.x ships with an old Guice + cglib combination. On Java 9+ module system,
constructor-injection proxies require reflective access to `java.lang`. Without the flag you get:

```
java.lang.reflect.InaccessibleObjectException:
  Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(...)
  accessible: module java.base does not "opens java.lang" to unnamed module
```

Add the flag when launching:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar velocity-1.0.10.jar
```

Or simply run the old Velocity on **Java 11** (no flag needed):

```bash
"C:\Program Files\Java\jdk-11\bin\java.exe" -jar velocity-1.0.10.jar
```

> This is a Velocity-internal dependency issue, unrelated to Mirage. Any plugin using `@Inject`
> constructor injection on 1.0.x + Java 9+ requires this flag.

## Java Compatibility (Important)

All artifacts are compiled to **Java 11 bytecode** (major 55) — this is the minimum Java version
supported by the bundled H2 2.5.252. As a result, **any JVM from Java 11 to Java 26 can load them
directly**, with no `--add-opens` or version workarounds required.

| Artifact | Bytecode | Runnable JVM |
|---|---|---|
| `mirage.jar` | major 55 | **Java 11 ~ 26** |
| `mirage-java8.jar` | major 52 | Java 8 ~ 26 |
| `mirage-nms.jar` | major 55 | **Java 11 ~ 26** (limited by the MC server JVM) |
| `mirage-folia.jar` | major 55 | **Java 11 ~ 26** |
| `mirage-velocity.jar` | major 55 | **Java 11 ~ 26** |
