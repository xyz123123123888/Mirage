# Mirage

> **A MySQL-compatible proxy backed by H2.**

客户端以为连的是 MySQL，实际上后端是 H2——正如沙漠中的海市蜃楼。

## 项目结构

```
Mirage/
├── frontier/          # 前沿独立项目（H2 后端的 MySQL 代理）
│   ├── src/mirage/core/  # 核心源码（包 mirage.core）
│   ├── lib/           # 依赖（H2 2.x / 1.4.200 + Connector/J）
│   ├── out11/ out8/   # 两套编译产物（构建生成，不入库）
│   ├── _debug_tests/  # 验证测试类
│   ├── build.bat/.ps1 # 构建（产出两个 fat jar）
│   ├── run.bat        # 自动降级启动
│   ├── mirage.properties  # 配置文件（缺失时自动生成）
│   ├── mirage.jar         # Java 11+（H2 2.x）
│   ├── mirage-java8.jar   # Java 8（H2 1.4.200）
│   └── README.md      # 详细文档
├── mc/                # Minecraft 分支
│   ├── velocity/      # [已完成] Velocity 代理插件
│   └── server/        # 服务端
│       ├── nms/       # [已完成] NMS（内置 Leaf 优化，单 jar）
│       ├── folia/     # [已完成] Folia 独立项目（区域化多线程分支）
│       └── fabric/    # Fabric（仅 1.21.11+，标记不稳定，源码就绪）
├── mirage-nms.jar     # 交付：NMS 多版本
├── mirage-folia.jar   # 交付：Folia
├── mirage-velocity.jar# 交付：Velocity
├── _artifacts/        # 测试残留归档（本地保留，.gitignore 排除，不参与上传）
└── docs/              # 架构与设计文档
```

## 两个独立项目

Mirage 包含**两个独立项目**：

1. **前沿（frontier）** —— 当前独立项目：H2 后端的 MySQL 协议代理，可独立运行
2. **MC 分支（mc）** —— 用于 Minecraft 服务器，再分代理/服务端

### MC 分支的版本策略

| 平台 | 版本策略 | jar 命名 | 状态 |
|---|---|---|---|
| **NMS 多版本** | 兼容 Bukkit / Spigot / Paper / Leaf；**字节码 Java 11**。Folia 支持已不再维护（检测到时打警告） | `mirage-nms.jar` | ✅ 已交付 |
| **Velocity 代理** | 仅 Velocity 端；字节码 Java 11 | `mirage-velocity.jar` | ✅ 已交付 |
| **Folia** | 区域化多线程分支，需 `folia-supported: true`；**唯一维护 Folia 的产物** | `mirage-folia.jar` | ✅ 已交付 |
| **Fabric** | 仅 1.21.11+（标记不稳定） | `mirage-fabric-Exptl-<MC版本>.jar` | ⚠️ 源码就绪，构建受阻 |

**前沿独立** 的 jar 直接命名 `mirage.jar`（无平台后缀）。

> **Folia 特别说明**：`mirage-nms.jar` 在 `plugin.yml` 中声明了 `folia-supported: true`。
> 这个声明的含义是「本插件不会调用 Folia 不安全的 API 而崩溃」——Mirage 不碰任何 MC API
> （世界/实体/区块/调度器），确实满足这个条件，在 Folia 上能正常工作。
>
> 但**内置的 Folia 兼容层不再投入维护**：Folia 的线程模型与 Paper 差异较大，
> 每次 Folia 更新都可能需要同步修改 NMS 的兼容层，维护成本高；且已有独立项目
> `mc/server/folia/` 专门跟进 Folia。在 NMS 里再维护一份会导致每次 Folia 更新都得同步改两处。
>
> 因此 `mirage-nms.jar` 在启动时会**检测 Folia 并打出醒目警告**，说明上述情况并
> 提示 Folia 用户改用 `mirage-folia.jar`。用户仍可选择继续使用 NMS 版（能跑），
> 但不保证、不维护。
>
> **Windows 服务端中文乱码**：日志里的中文警告在 Windows 上可能显示为 `?`，
> 这是 JVM 默认使用 GBK 编码造成的（整个服务端所有中文都会受影响，不只 Mirage）。
> 启动时加 JVM 参数即可解决：
> ```
> -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
> ```

### 交付状态说明

**NMS 与 Velocity 两个插件均已在真实服务端上完成实机验证。**

#### NMS + Paper（Paper 1.21.4 build 232 + Java 25）

```
[PluginInitializerManager] Initialized 1 plugin
 - Mirage (8.4.0-mirage)
[01:26:03 INFO]: [Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
[01:26:11 INFO]: Done (141.347s)! For help, type "help"
```

#### Velocity 代理（Velocity 4.3.1 build 45 + Java 25）

```
[02:12:06 INFO]: Loaded plugin mirage 8.4.0-mirage by Mirage
[02:12:18 INFO] [mirage]: Mirage MySQL proxy ready on 0.0.0.0:3306
[02:12:18 INFO]: Listening on /[0:0:0:0:0:0:0:0]:25565
[02:12:18 INFO]: Done (20.18s)!
```

#### Leaf 服务端（Paper 1.21.4 build 232 + Java 25，装 mirage-nms.jar）

```
[02:23:30 INFO]: [Mirage] Mirage (Leaf) loading...
[02:23:35 INFO]: [Mirage] Mirage (Leaf) enabling, starting MySQL proxy...
[02:23:55 INFO]: [Mirage] Mirage MySQL proxy ready on 0.0.0.0:3306
[02:23:57 INFO]: Done (288.619s)! For help, type "help"
```

> 注意：Leaf 与 NMS **不能同时安装**——两者插件名相同（Mirage），
> Paper 会报 `Ambiguous plugin name 'Mirage'`。按目标平台二选一部署。

#### 优雅关闭实机验证（七个环境均已完成）

关闭流程（主动断开客户端 → 等待线程退出 → 关闭会话连接 → CHECKPOINT 落盘 → 关闭 H2 → 关闭日志）
已在真实服务端上验证，**客户端保持连接不关的情况下停止服务端，重启后数据零丢失**：

| 平台 | 客户端写入 | 关闭后校验 | 结果 |
|---|---|---|---|
| NMS（Paper 1.21.4） | 11 行 | `rows=11 maxId=11` | ✅ 零丢失 |
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

#### 实机验证覆盖（NMS / Leaf / Folia / Velocity 四个平台均已完成）

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
  详见 `mc/server/fabric/README.md`。

### 构建结构（NMS + Leaf 共项目，Folia 独立）

**NMS 项目内置 Leaf 优化**——Leaf 是 Paper 的优化端，API 与 Paper 同源，Mirage 不碰 MC API，对 Leaf 天然兼容：

```
mc/server/nms/
├── src/main/java/mirage/mc/nms/MiragePlugin.java   → mirage-nms.jar   (plugin.yml)
├── src/main/resources/plugin.yml
└── build.ps1                                       → 产出 mirage-nms.jar
```

**Folia 是独立项目**——区域化多线程对 API 与线程模型的约束远比 Leaf 严苛，单独维护：

```
mc/server/folia/
├── src/main/java/mirage/mc/folia/MirageFoliaPlugin.java
├── src/main/resources/plugin.yml    → mirage-folia.jar
├── lib/                             (paper-api + folia-api)
└── build.ps1
```

> Folia 的元数据文件名**必须是 `plugin.yml`**（Folia 不识别 `folia-plugin.yml`），
> 内容需带 `folia-supported: true`，否则 Folia 直接拒绝加载。
> 两个项目都用 `--release 11` 编译，字节码统一 major 55。

### jar 命名规则

```
<项目名 必须>-<平台>.jar                     # 一般平台
mirage.jar                                   # 前沿独立（无后缀）
mirage-nms.jar                               # NMS 多版本
mirage-velocity.jar                          # Velocity 代理
mirage-folia.jar                             # Folia（区域化多线程分支）
mirage-fabric-Exptl-<兼容的MC版本>.jar        # Fabric（Exptl = 实验/不稳定，带 MC 版本）
#   例: mirage-fabric-Exptl-1.21.11.jar
#       mirage-fabric-Exptl-26.2.jar
```

### 启动策略（参考 Geyser）

- 插件/模组**优先以最高 API 顺序启动**
- 随后**故意阻塞启动**——防止绑定我们的插件/模组（如反作弊）先加载而因无 MySQL 连接崩溃
- MC 环境**不做单独版本兼容**（除 Fabric），直接用最简单方法跑 1.8+
- 可复用 API 少 → **自研 API 防崩溃**

## Java 版本兼容（重要）

所有产物的字节码编译目标统一为 **Java 11**（major 55）——这是打包的 H2 2.5.252 自身支持的最低 Java 版本。
因此 **Java 11 ~ 26 的 JVM 都能直接加载**，无需任何 `--add-opens` 或版本绕过参数。

| 产物 | 字节码 | 可运行 JVM |
|---|---|---|
| `mirage.jar` | major 55 | **Java 11 ~ 26** |
| `mirage-java8.jar` | major 52 | Java 8 ~ 26 |
| `mirage-nms.jar` | major 55 | **Java 11 ~ 26**（受 MC 服务端 JVM 版本约束） |
| `mirage-folia.jar` | major 55 | **Java 11 ~ 26** |
| `mirage-velocity.jar` | major 55 | **Java 11 ~ 26** |

### 实机验证矩阵

| 组合 | 结果 |
|---|---|
| Paper 1.21.4 + Java 25 + NMS | ✅ 启动 / 关闭 / 数据落盘 |
| Paper 1.21.4 + Java 25 + Leaf | ✅ 启动 / 关闭 / 数据落盘 |
| Velocity 4.3.1 + Java 25 | ✅ 启动 / 关闭 / 数据落盘 |
| **真实 Leaf 1.21.4 + Java 25 + mirage-nms.jar（内置 Leaf 优化）** | ✅ 启动 / JDBC / 函数 / 关闭 / **22 行数据零丢失** |
| **Folia 1.19.4 + Java 17 + Folia 插件** | ✅ 启动 / JDBC / 函数 / 关闭 / **18 行数据零丢失** |
| **Folia 26.3 + Java 25 + Folia 插件** | ✅ 启动 / JDBC / 函数 / 关闭 / **22 行数据零丢失** |
| **Paper 1.16.5 + Java 11.0.2 + NMS** | ✅ 启动 / JDBC / 函数 / 关闭 / **15 行数据零丢失** |
| **mirage.jar + Java 16.0.2（独立运行）** | ✅ 正常监听 |

> 注：Paper 1.16.5 官方推荐 Java 16，用 Java 11 启动需加 `-DPaper.IgnoreJavaVersion=true`；
> Paper 1.16.4 及更早版本官方推荐 Java 11，无需该参数。Mirage 插件本身在 Java 11 上工作正常。

## 文件位置（标准目录）

| 部署形态 | 数据目录 | 配置 / 数据库 / 日志 |
|---|---|---|
| 前沿独立（`java -jar mirage.jar`） | 启动时的工作目录 | `./mirage.properties`、`./data/`、`./logs/` |
| Bukkit / Spigot / Paper（NMS） | `plugins/Mirage/` | `plugins/Mirage/mirage.properties`、`plugins/Mirage/data/`、`plugins/Mirage/logs/` |
| Leaf | `plugins/Mirage/` | 同上 |
| Velocity | `plugins/mirage/` | `plugins/mirage/mirage.properties`、`plugins/mirage/data/`、`plugins/mirage/logs/` |
| Fabric（源码骨架） | `config/mirage/` | 同上结构 |

插件启动时会在日志里打印实际的 **data folder** 绝对路径，方便定位。
首次启动若配置不存在，会**自动生成一份带完整中文注释的 `mirage.properties`**。
配置里写的相对路径（`db.path`、`log.file`）都相对该数据目录解析，方便整体搬迁。

## 配置（mirage.properties）

自动生成的配置包含全部可调项与注释，修改后**重启生效**。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.host` | `0.0.0.0` | 绑定地址。`0.0.0.0` = 所有网卡，`127.0.0.1` = 仅本机 |
| `server.port` | `3306` | MySQL 监听端口 |
| `server.name` | `Mirage` | 服务器名（客户端可见） |
| `server.version` | `8.4.0-mirage` | `SELECT VERSION()` 返回值 |
| `server.backlog` | `512` | 连接队列大小 |
| `server.maxConn` | `256` | 最大并发连接数（线程池上限，最小 8） |
| `db.path` | `./data/h2db` | Java 11+ / H2 2.x 库路径 |
| `db.path.legacy` | `./data/h2db-j8` | Java 8 / H2 1.4.200 库路径 |
| `db.user` / `db.password` | `sa` / 空 | **H2 内嵌库的凭据（仅内部使用）**，不是客户端登录凭据 |
| `db.autoServer` | `true` | 是否允许多进程共享库文件；无 DNS 环境下开启会让首次开库等 20~30 秒，单进程可设 `false` |
| `log.queries` | `false` | 是否逐条记录 SQL（性能杀手，排障时再开） |
| `log.console` | `true` | 是否输出到控制台 |
| `log.file` | `./logs/mirage.log` | 日志文件路径 |
| `log.level` | `INFO` | 最低输出级别：`DEBUG` / `INFO` / `WARN` / `ERROR` |
| `auth.strict` | `true` | 严格校验客户端密码；`false` = 宽松模式（未知用户直接放行，**仅调试用**） |
| `auth.username` | `root` | **客户端登录账号名**（单账号） |
| `auth.password` | `root` | **客户端登录密码**；留空 = 免密（仅限本机调试） |

> **登录凭据说明**：客户端连 Mirage 用的是 `auth.username` / `auth.password` 指定的单一账号（MySQL 协议层认证，
> 支持 `mysql_native_password` 与 `caching_sha2_password`）；
> `db.user`/`db.password` 只是 Mirage 进程内部访问 H2 文件库的凭据，与客户端无关。
> 默认账号是 `root / root`，**对外网暴露前务必改掉**，并把 `server.host` 收紧到 `127.0.0.1` 或内网地址。
>
> **仅支持单账号**：出于安全考虑，Mirage 不再支持多账号，也不再响应 `CREATE USER` / `DROP USER`
> （只返回 OK 让客户端继续）。旧格式 `user.*` / `users=a:b,c:d` 已停用，仅保留注释。

## 关闭流程（防丢数据）

插件/程序作为**最后退出者**，关闭顺序固定：

1. 关闭 `ServerSocket`，不再接受新连接
2. **主动断开所有客户端连接**（处理线程立即退出，不再产生新 SQL）
3. 等待连接线程池收敛（上限 10 秒，超时强制中断）
4. 关闭所有会话的 H2 连接（未提交事务回滚、已提交的保留）
5. `CHECKPOINT` 落盘 + 关闭主 H2 连接，释放库锁
6. 冲刷并关闭日志文件

独立运行时由 JVM shutdown hook 触发同一套流程；MC 插件在 `onDisable` /
`onProxyShutdown` 中调用。实测：保持客户端连接不关直接停服，重启后数据完整。

## 快速开始（前沿）

```powershell
cd frontier
build.bat    # 生成 out11 / out8
run.bat      # 检测 java -version，自动选 mirage.jar 或 mirage-java8.jar
```

详见 [frontier/README.md](frontier/README.md)。

## 许可证

本项目采用 **Apache License 2.0** 开源。

- 完整许可证文本：[LICENSE](LICENSE)
- 第三方组件声明与版权归属：[NOTICE](NOTICE)

### 你可以

- 自由使用、修改、分发本项目（包括商业用途）
- 将本项目集成进闭源产品

### 你需要

- 保留 `LICENSE` 与 `NOTICE` 文件，或在分发物中保留版权声明
- 若修改了源文件，在修改的文件中注明变更

### 我们不做

- 不要求你的衍生作品开源（非 copyleft）
- 不提供任何担保（详见 LICENSE 第 7、8 条）

### 关于「无 GPL 传染」

Mirage 不包含任何 MySQL 源代码，MySQL 协议为独立实现。
Paper / Folia 的 API 仅在编译期引用、**不打包进交付 jar**，
因此不构成 GPL 传染。详见 [NOTICE](NOTICE) 中的组件清单。

### 引用本项目

如果你在文档、博客或产品中提及本项目，欢迎链接到：

```
Mirage — A MySQL-compatible proxy backed by H2
https://github.com/<your-org>/mirage
```

（此为社区礼仪建议，非许可证强制要求。）
