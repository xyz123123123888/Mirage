# Mirage

> **A MySQL-compatible proxy backed by H2.**

Clients think they are talking to MySQL; the backend is actually H2 — a mirage in the desert.

🌐 [中文文档](../README.md) | **English**

## Documentation

| Document | Content |
|---|---|
| [STRUCTURE.md](STRUCTURE.md) | Project layout, two independent projects, MC branch version strategy, build structure, jar naming rules, startup strategy |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Velocity compatibility (1.0 ~ 4.x), Java compatibility (11 ~ 26) |
| [TESTING.md](TESTING.md) | Delivery status, per-platform test logs, live verification matrix |
| [CONFIG.md](CONFIG.md) | File locations (standard directories), configuration options, shutdown sequence |
| [LICENSE.md](LICENSE.md) | Apache-2.0 license, third-party components, no GPL contamination |
| [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | Architecture overview (Chinese) |
| [../frontier/README.md](../frontier/README.md) | Frontier standalone project (Chinese) |
| [../mc/server/nms/README.md](../mc/server/nms/README.md) | NMS plugin (Chinese) |
| [../mc/server/fabric/README.md](../mc/server/fabric/README.md) | Fabric skeleton (Chinese) |

## At a Glance

- **What it is**: A MySQL wire-protocol proxy backed by H2. Zero MySQL code, no GPL contamination.
- **Two deployment modes**:
  1. **Standalone**: `java -jar mirage.jar` (Java 11+) or `java -jar mirage-java8.jar` (Java 8)
  2. **As a Minecraft plugin**: NMS (Leaf optimizations built in), Folia, Velocity
- **Five delivered jars**:

  | jar | Platform | Bytecode |
  |---|---|---|
  | `mirage.jar` | Frontier standalone (H2 2.x) | major 55 (Java 11+) |
  | `mirage-java8.jar` | Frontier standalone (H2 1.4.200) | major 52 (Java 8+) |
  | `mirage-nms.jar` | Bukkit / Spigot / Paper / Leaf (Leaf built in) | major 55 |
  | `mirage-folia.jar` | Folia (regionized multithreading) | major 55 |
  | `mirage-velocity.jar` | Velocity proxy (1.0 ~ 4.x) | major 55 |

- **Configuration**: On first launch, a fully commented `mirage.properties` is generated in the data directory. See [CONFIG.md](CONFIG.md).
- **License**: Apache-2.0. See [LICENSE.md](LICENSE.md).

## Quick Start (Frontier Standalone)

```powershell
cd frontier
build.bat    # Generates out11 / out8
run.bat      # Detects java -version and picks mirage.jar or mirage-java8.jar
```

Or use the prebuilt jars directly:

```bash
java -jar mirage.jar          # Java 11+
java -jar mirage-java8.jar    # Java 8
```

## Quick Start (Minecraft Plugin)

1. Pick the jar for your platform (NMS / Folia / Velocity)
2. Drop it into the server `plugins/` directory
3. Start the server; on first launch a configuration file is generated under `plugins/<plugin-name>/`
4. Connect with `root / root` to `localhost:3306` (**change the password before exposing to the internet**)

See [STRUCTURE.md](STRUCTURE.md) and the platform-specific READMEs for details.
