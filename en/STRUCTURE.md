# Mirage — Structure & Build

> Back to [README.md](README.md) · [中文版](../STRUCTURE.md)

## Project Layout

```
Mirage/
├── frontier/          # Frontier standalone project (MySQL proxy backed by H2)
│   ├── src/mirage/core/  # Core sources (package mirage.core)
│   ├── lib/           # Dependencies (H2 2.x / 1.4.200 + Connector/J)
│   ├── out11/ out8/   # Two build outputs (generated, not tracked)
│   ├── _debug_tests/  # Verification test classes
│   ├── build.bat/.ps1 # Build (produces two fat jars)
│   ├── run.bat        # Auto-fallback launcher
│   ├── mirage.properties  # Config file (auto-generated if missing)
│   ├── mirage.jar         # Java 11+ (H2 2.x)
│   ├── mirage-java8.jar   # Java 8 (H2 1.4.200)
│   └── README.md      # Detailed docs
├── mc/                # Minecraft branch
│   ├── velocity/      # [Done] Velocity proxy plugin
│   └── server/        # Server side
│       ├── nms/       # [Done] NMS (Leaf optimizations built in, single jar)
│       ├── folia/     # [Done] Folia standalone project (regionized multithreading)
│       └── fabric/    # Fabric (1.21.11+ only, experimental, sources ready)
├── mirage-nms.jar     # Deliverable: NMS
├── mirage-folia.jar   # Deliverable: Folia
├── mirage-velocity.jar# Deliverable: Velocity
├── _artifacts/        # Test residue archive (local only, gitignored, never uploaded)
└── docs/              # Architecture & design docs
```

## Two Independent Projects

Mirage consists of **two independent projects**:

1. **Frontier** — the current standalone project: a MySQL protocol proxy backed by H2, runnable on its own
2. **MC branch** — for Minecraft servers, split into proxy / server sides

### MC Branch Version Strategy

| Platform | Strategy | jar name | Status |
|---|---|---|---|
| **NMS (multi-version)** | Compatible with Bukkit / Spigot / Paper / Leaf; **bytecode Java 11**. Folia support no longer maintained (prints a warning when detected) | `mirage-nms.jar` | Delivered |
| **Velocity proxy** | Theoretically compatible with all Velocity versions (1.0 ~ 4.x); bytecode Java 11 | `mirage-velocity.jar` | Delivered |
| **Folia** | Regionized multithreading branch; requires `folia-supported: true`; **the only artifact that maintains Folia support** | `mirage-folia.jar` | Delivered |
| **Fabric** | 1.21.11+ only (marked unstable) | `mirage-fabric-Exptl-<MC version>.jar` | Sources ready, build blocked |

**Frontier standalone** jars are named `mirage.jar` (no platform suffix).

> **Folia note**: `mirage-nms.jar` declares `folia-supported: true` in `plugin.yml`.
> This declaration means "the plugin will not crash by calling Folia-unsafe APIs" — Mirage
> touches no MC APIs (world/entity/chunk/scheduler), so it genuinely qualifies and works on Folia.
>
> However, the **built-in Folia compatibility layer is no longer maintained**: Folia's threading
> model diverges significantly from Paper, and every Folia update may require syncing the NMS
> compatibility layer. A dedicated project `mc/server/folia/` already tracks Folia; maintaining
> a second copy inside NMS would mean syncing two places on every Folia update.
>
> So `mirage-nms.jar` **detects Folia at startup and prints a prominent warning**, explaining
> the situation and pointing Folia users to `mirage-folia.jar`. Users may still choose to run
> the NMS build (it works), but it is not guaranteed nor maintained.
>
> **Chinese garbled on Windows servers**: Chinese warnings in the log may appear as `?` on
> Windows because the JVM defaults to GBK (this affects all Chinese text in the server, not
> just Mirage). Add JVM flags at startup to fix:
>
> ```
> -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
> ```

## Build Structure (NMS + Leaf share a project, Folia standalone)

**NMS project has Leaf optimizations built in** — Leaf is a Paper-optimized fork; its API is
compatible with Paper, and Mirage touches no MC APIs, so it works on Leaf without extra code:

```
mc/server/nms/
├── src/main/java/mirage/mc/nms/MiragePlugin.java   → mirage-nms.jar   (plugin.yml)
├── src/main/resources/plugin.yml
└── build.ps1                                       → produces mirage-nms.jar
```

**Folia is a standalone project** — regionized multithreading imposes much stricter constraints
on APIs and threading models than Leaf, so it is maintained separately:

```
mc/server/folia/
├── src/main/java/mirage/mc/folia/MirageFoliaPlugin.java
├── src/main/resources/plugin.yml    → mirage-folia.jar
├── lib/                             (paper-api + folia-api)
└── build.ps1
```

> Folia's metadata filename **must be `plugin.yml`** (Folia does not recognize
> `folia-plugin.yml`), and the content must include `folia-supported: true`, otherwise Folia
> refuses to load the plugin. Both projects compile with `--release 11`, bytecode major 55.

## jar Naming Rules

```
<project-name>-<platform>.jar                # Generic platform
mirage.jar                                   # Frontier standalone (no suffix)
mirage-nms.jar                               # NMS multi-version
mirage-velocity.jar                          # Velocity proxy
mirage-folia.jar                             # Folia (regionized multithreading)
mirage-fabric-Exptl-<MC version>.jar         # Fabric (Exptl = experimental, with MC version)
#   e.g. mirage-fabric-Exptl-1.21.11.jar
#        mirage-fabric-Exptl-26.2.jar
```

## Startup Strategy (inspired by Geyser)

- Plugins/mods **start as early as possible** in the highest API order
- Then **intentionally block startup** — prevents plugins/mods that depend on Mirage (e.g. anti-cheats)
  from loading first and crashing because no MySQL connection is available
- MC environments **do not do per-version compatibility** (except Fabric); use the simplest approach for 1.8+
- Few reusable APIs → **build our own minimal API layer to prevent crashes**
