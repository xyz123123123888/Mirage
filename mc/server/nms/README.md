# Mirage — NMS 插件（Bukkit / Spigot / Paper / Leaf 同源服务端）

把 Mirage 的 MySQL 代理嵌入 MC 服务端，让其他插件以为连的是 MySQL，实际后端是 H2。

## 交付物

产出一个 jar：`mirage-nms.jar`（内置 Leaf 优化，兼容所有同源服务端）

- `mirage-nms.jar` — 内置 Leaf 优化，兼容 Bukkit / Spigot / Paper / Leaf 等所有同源服务端

包含 Mirage 核心 + H2 + 插件类 + 元数据，**单文件部署**，放入服务端 `plugins/` 目录即可。

## 生命周期（已用 harness 验证）

\`\`\`
onLoad     -> 记录实例（STARTUP 优先级最早加载）
onEnable   -> MirageConfig.load() + MirageServer.start()
              等待端口就绪（最多 10 秒）
onDisable  -> MirageServer.stop()
\`\`\`

实测日志（Paper 1.21.4 / 真实 Leaf 1.21.4 / Paper 1.16.5 + Java 11 均验证过）：
```
[Mirage] Loading server plugin Mirage v8.4.0-mirage
[Mirage] Mirage loading (STARTUP priority)...
[Mirage] Mirage enabling, starting MySQL proxy...
[Mirage] Mirage data folder: ...\plugins\Mirage
[Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
...
[Mirage] Disabling Mirage v8.4.0-mirage
[Mirage] Mirage disabling: closing connections and flushing database...
[Mirage] [Db] 数据库已安全关闭
[Mirage] Mirage stopped safely (data flushed).
```

## 构建

\`\`\`powershell
# 需要 lib/paper-api.jar（真实 API，仅编译期用；不打包进产物）
powershell -ExecutionPolicy Bypass -File build.ps1
# 2) 构建
powershell -ExecutionPolicy Bypass -File build.ps1
\`\`\`

产物：仓库根 `mirage-nms.jar`（含 mirage.core + org.h2 + 插件类 + plugin.yml）

## 配置

首次启动会在 `plugins/Mirage/` 下自动生成带完整中文注释的 `mirage.properties`
（端口/地址/单账号凭据/数据库路径/日志级别等），修改后重启生效。
数据库与日志也放在该目录下（`plugins/Mirage/data/`、`plugins/Mirage/logs/`）。

## 版本策略

- 面向 Bukkit / Spigot / Paper / Leaf 等**所有同源服务端**（均由水龙头派生）
- **内置 Leaf 优化**：Leaf 是 Paper 的优化端，API 与 Paper 同源；Mirage 不使用任何 MC API（世界/实体/区块/调度器），对 Leaf 天然兼容，无需单独适配
- **Folia 支持已不再维护**：`plugin.yml` 声明了 `folia-supported: true`（意为「本插件不会调用 Folia 不安全的 API 而崩溃」，Mirage 不碰任何 MC API，确实满足），本 jar 在 Folia 上能正常加载运行。但内置的 Folia 兼容层不再投入维护：Folia 线程模型与 Paper 差异较大，每次 Folia 更新都可能需要同步修改 NMS 兼容层，维护成本高；且已有独立项目 `mc/server/folia/` 专门跟进。插件启动时会**检测 Folia 并打出醒目警告**，给出改用 `mirage-folia.jar` 的指引

> **Windows 中文乱码**：若日志里的中文显示为 `?`，是 JVM 默认 GBK 编码所致（整个服务端都受影响）。
> 加 JVM 参数 `-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8` 可解决。
- **不含** Fabric（仅 1.21.11+，标记不稳定）

两个插件 jar 的字节码统一为 **Java 11（major 55）**，Java 11 ~ 26 的 JVM 均可加载。

## 依赖声明建议

其他需要 MySQL 的插件，建议在自身 plugin.yml 声明：
\`\`\`yaml
softdepend: [Mirage]
\`\`\`
配合本插件 \`load: STARTUP\`，可保证其 onEnable 时 MySQL 端口已就绪
（防止反作弊等模组因无 MySQL 连接而崩溃）。
