# Mirage — 实机验证记录

> 返回 [README.md](README.md)

## 交付状态说明

**NMS / Folia / Velocity 三个插件均已在真实服务端上完成实机验证**，Velocity 额外覆盖了 1.0.10 / 3.1.0 / 4.0.0 / 4.3.1 四个版本（详见 [COMPATIBILITY.md](COMPATIBILITY.md)）。

### NMS + Paper（Paper 1.21.4 build 232 + Java 25）

```
[PluginInitializerManager] Initialized 1 plugin
 - Mirage (8.4.0-mirage)
[01:26:03 INFO]: [Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
[01:26:11 INFO]: Done (141.347s)! For help, type "help"
```

### Velocity 代理（Velocity 4.3.1 build 45 + Java 25）

```
[02:12:06 INFO]: Loaded plugin mirage 8.4.0-mirage by Mirage
[02:12:18 INFO] [mirage]: Mirage MySQL proxy ready on 0.0.0.0:3306
[02:12:18 INFO]: Listening on /[0:0:0:0:0:0:0:0]:25565
[02:12:18 INFO]: Done (20.18s)!
```

### Leaf 服务端（Paper 1.21.4 build 232 + Java 25，装 mirage-nms.jar）

```
[Mirage] Loading server plugin Mirage v8.4.0-mirage
[Mirage] Mirage loading (STARTUP priority)...
[Mirage] Mirage enabling, starting MySQL proxy...
[Mirage] Mirage data folder: ...\plugins\Mirage
[Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
Done (99.115s)! For help, type "help"
```

> 注意：`mirage-nms.jar` 是单 jar，内置 Leaf 优化，不需要额外安装 `mirage-leaf.jar`。

### 优雅关闭实机验证（七个环境均已完成）

关闭流程（主动断开客户端 → 等待线程退出 → 关闭会话连接 → CHECKPOINT 落盘 → 关闭 H2 → 关闭日志）
已在真实服务端上验证，**客户端保持连接不关的情况下停止服务端，重启后数据零丢失**：

| 平台 | 客户端写入 | 关闭后校验 | 结果 |
|---|---|---|---|
| NMS（Paper 1.21.4） | 11 行 | `rows=11 maxId=11` | ✅ 零丢失 |
| Velocity 1.0.10（需 `--add-opens`） | — | 启动 / 关闭通过（未做数据落盘验证） | ✅ |
| Velocity 3.1.0 | — | 启动 / 关闭通过（未做数据落盘验证） | ✅ |
| Velocity 4.0.0 | — | 启动 / 关闭通过（未做数据落盘验证） | ✅ |
| Velocity 4.3.1 | 9 行 | `rows=9 maxId=9` | ✅ 零丢失 |
| Leaf（Paper 1.21.4） | 3 行 | `rows=3 maxId=3` | ✅ 零丢失 |

三端日志均输出：
```
Disabling Mirage v8.4.0-mirage
Mirage disabling: closing connections and flushing database...
[Db] 数据库已安全关闭
Mirage stopped safely (data flushed).
```

标准目录同样验证通过：`plugins/Mirage/mirage.properties`、`plugins/Mirage/data/h2db`、
`plugins/Mirage/logs/mirage.log`（Velocity 为 `plugins/mirage/`），首次启动自动生成带注释的配置文件。

> 修复记录：`Console` 原先直接写 `System.out`，在 Paper 上触发
> `Nag author(s): ... usage of System.out/err.print` 警告。现已全部改走 `LogSink`，
> 由宿主 logger 接管后警告消失，且输出不再带 `[STDOUT]` 前缀。

### 实机验证覆盖（NMS / Leaf / Folia / Velocity 四个平台均已完成）

- 3306 端口由宿主进程内监听，JDBC 可正常连接、建表、增删改查
- MySQL 兼容函数全部实测通过：REVERSE / SUBSTRING_INDEX / FIELD / DATE / TIME / DATE_FORMAT
- 服务端日志无 ERROR、无 Nag 警告（核心日志已通过 LogSink 接入宿主 logger）
- Leaf 单独部署时日志 ERROR 行数为 0
- **Velocity 插件元数据必须用 JSON 格式**（`velocity-plugin.json`），写成 YAML 会报
  `Expected BEGIN_OBJECT but was STRING`
- **frontier 两个 fat jar 已验证独立运行**：`java -jar mirage.jar`（Java 11+）与
  `java -jar mirage-java8.jar`（Java 8）均正常监听 3306 并提供完整 MySQL 服务

- **NMS / Leaf / Folia / Velocity 四个服务端/代理 jar 均已构建**，内含 `mirage.core` + `org.h2` + 插件类 + 元数据文件，单文件部署。
- **均已用真实 API 编译**（从 repo.papermc.io 下载）：
  - Paper API 1.21.4-R0.1-20250925.065901-231（NMS / Leaf）
  - Velocity API 4.3.1 + slf4j-api 2.0.9 + guice 5.1.0（Velocity）
- 插件类字节码版本：**全部 major 55（Java 11）**，与 H2 2.5.252 的下限一致。
- 构建注意：Paper API 1.21.4 与 Velocity API 4.3.1 的**依赖 jar 自身**含 Java 21+/25 字节码，
  但只用于编译期；我们的产物统一用 JDK 25 的 `--release 11` 编译，故运行时只需 Java 11+。
- **H2 ALIAS 必须用 `FOR "mirage.core.MySqlFunctions.xxx"` 引用预编译方法**，
  不能用 `AS $$Java源码$$`——后者会让 H2 在 Java 25 下动态编译出 class 69，
  触发 `Unsupported class file major version 69`。
- **Fabric 只交付源码骨架**：Fabric 模组必须用 Gradle + Loom 构建，且需联网拉取
  Minecraft / yarn mappings / Fabric Loader，本机网络不可达，无法产出可运行 jar。
  详见 [mc/server/fabric/README.md](mc/server/fabric/README.md)。

## 实机验证矩阵

| 组合 | 结果 |
|---|---|
| Paper 1.21.4 + Java 25 + NMS | ✅ 启动 / 关闭 / 数据落盘 |
| Paper 1.21.4 + Java 25 + Leaf | ✅ 启动 / 关闭 / 数据落盘 |
| Velocity 1.0.10 + Java 25（需 `--add-opens`） | ✅ 启动 / 关闭（未做数据落盘验证） |
| Velocity 3.1.0 + Java 25 | ✅ 启动 / 关闭（未做数据落盘验证） |
| Velocity 4.0.0 + Java 25 | ✅ 启动 / 关闭（未做数据落盘验证） |
| Velocity 4.3.1 + Java 25 | ✅ 启动 / 关闭 / 数据落盘 |
| **真实 Leaf 1.21.4 + Java 25 + mirage-nms.jar（内置 Leaf 优化）** | ✅ 启动 / JDBC / 函数 / 关闭 / **22 行数据零丢失** |
| **Folia 1.19.4 + Java 17 + Folia 插件** | ✅ 启动 / JDBC / 函数 / 关闭 / **18 行数据零丢失** |
| **Folia 26.3 + Java 25 + Folia 插件** | ✅ 启动 / JDBC / 函数 / 关闭 / **22 行数据零丢失** |
| **Paper 1.16.5 + Java 11.0.2 + NMS** | ✅ 启动 / JDBC / 函数 / 关闭 / **15 行数据零丢失** |
| **mirage.jar + Java 16.0.2（独立运行）** | ✅ 正常监听 |

> 注：Paper 1.16.5 官方推荐 Java 16，用 Java 11 启动需加 `-DPaper.IgnoreJavaVersion=true`；
> Paper 1.16.4 及更早版本官方推荐 Java 11，无需该参数。Mirage 插件本身在 Java 11 上工作正常。
