# Mirage — 配置与运行时

> 返回 [README.md](README.md)

## 文件位置（标准目录）

| 部署形态 | 数据目录 | 配置 / 数据库 / 日志 |
|---|---|---|
| 前沿独立（`java -jar mirage.jar`） | 启动时的工作目录 | `./mirage.properties`、`./data/`、`./logs/` |
| Bukkit / Spigot / Paper（NMS） | `plugins/Mirage/` | `plugins/Mirage/mirage.properties`、`plugins/Mirage/data/`、`plugins/Mirage/logs/` |
| Leaf | `plugins/Mirage/` | 同上 |
| Folia | `plugins/Mirage/` | 同上 |
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
