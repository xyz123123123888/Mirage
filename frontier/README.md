# Mirage — Frontier

> **A MySQL-compatible proxy backed by H2.**
> 前沿独立项目（H2 后端的 MySQL 协议代理）

MySQL 8.x 协议伪服务端，后端挂 H2 数据库。让 MySQL 客户端（HeidiSQL / Navicat / JDBC）
连上、建库、建表、增删改数据、导出。**零 MySQL 代码，无 GPL 传染。**

## 核心能力

### 协议 + 认证（真实验证）
- HandshakeV10、COM_QUERY/INIT_DB/PING/QUIT/CHANGE_USER、结果集编码、OK/ERR 包
- **caching_sha2_password full path (RSA)**：AuthMoreData(0x04) → 公钥 → RSA 解密验证
  - 关键：nonce 只用可打印 ASCII（否则 Connector/J 按 UTF-8 解码替换非法字节为 U+FFFD）
- **mysql_native_password**：SHA1 链式校验 + 空密码特判
- 严格模式：错误密码被拒绝

### 数据库 / 表
- CREATE/DROP DATABASE（支持数字 `123`、横线、中文名，自动加引号）
- USE 切换、每连接独立 schema 隔离
- CREATE/ALTER/DROP/TRUNCATE/RENAME TABLE
- 复杂 DDL：ENGINE/CHARSET/COLLATE/COMMENT/索引/AUTO_INCREMENT/PRIMARY KEY
- 视图创建/修改（ALTER VIEW → CREATE OR REPLACE VIEW）
- 外键、索引、保留字表名/列名、中文列名

### 数据操作
- SELECT/INSERT（多行）/UPDATE/DELETE
- **事务**：BEGIN/START TRANSACTION/COMMIT/ROLLBACK/SET autocommit（真实回滚）
- **DML 变体**：ON DUPLICATE KEY UPDATE / INSERT IGNORE / REPLACE INTO
- 数据类型：INT/BIGINT/DECIMAL/FLOAT/DOUBLE/DATE/TIME/DATETIME/TIMESTAMP/YEAR/
  VARCHAR/TEXT 家族/BLOB/JSON/ENUM/BOOLEAN/UNSIGNED/整数宽度/时间精度

### 函数（MySQL 兼容）
- **日期时间**：NOW/CURDATE/CURTIME/YEAR/MONTH/DAY/HOUR/MINUTE/SECOND/DATE/TIME/
  DATE_ADD/DATE_SUB/DATEDIFF/TIMESTAMPDIFF/UNIX_TIMESTAMP/STR_TO_DATE/EXTRACT/
  LAST_DAY/**DATE_FORMAT**
- **字符串**：CONCAT/CONCAT_WS/SUBSTRING/LEFT/RIGHT/LPAD/RPAD/LOCATE/INSTR/
  REPEAT/UPPER/LOWER/TRIM/REPLACE/LENGTH/CHAR_LENGTH/**REVERSE**/
  **SUBSTRING_INDEX**/**FIELD**
- **条件**：IF/IFNULL/COALESCE/CASE
- **聚合**：COUNT/SUM/AVG/MIN/MAX/GROUP_CONCAT
- **数学**：ROUND/CEIL/FLOOR/ABS/MOD

### 方言翻译层（MySQL → H2）
- `SET('a','b')` 类型 → VARCHAR
- `INT(11)`/`FLOAT(10,2)` 显示宽度 → 去宽度
- `IF(c,a,b)` → CASEWHEN
- `DATEDIFF(a,b)` → 取负（H2 方向相反）
- `DATE_ADD/SUB(d, INTERVAL n UNIT)` → DATEADD
- `STR_TO_DATE(s, '%Y-%m-%d')` → PARSEDATETIME（格式串自动转换）
- `UNSIGNED` 修饰符 → 去除
- `COLLATE/ENGINE/CHARSET/COMMENT` 表选项 → 剥离

### H2 别名函数（注册 MySQL 缺失函数）
REVERSE / SUBSTRING_INDEX / FIELD / DATE / TIME / DATE_FORMAT

### 元数据
- SHOW × 20+：DATABASES/TABLES/CREATE TABLE（含 AUTO_INCREMENT+PRIMARY KEY）/CREATE DATABASE/
  COLUMNS/TABLE STATUS/INDEX/KEYS/VARIABLES/STATUS/ENGINES/CHARSET/COLLATION/
  TRIGGERS/EVENTS/PROCESSLIST/GRANTS
- information_schema × 20+ 表（**显示 MySQL 标准信息表，非 H2 内部表**）
- mysql.innodb_*
- **@@ 变量全集（58+ 个）**：version/innodb_version/sql_mode/transaction_read_only 等

### 兼容处理
- 存储过程/函数/触发器/事件：H2 不支持，优雅忽略
- LOCK/UNLOCK TABLES、SET FOREIGN_KEY_CHECKS、SET NAMES、FLUSH PRIVILEGES
- ANALYZE/OPTIMIZE/CHECK/REPAIR/CHECKSUM TABLE
- 版本注释 `/*!40100 ... */`
- 服务端预处理（COM_STMT_PREPARE）：明确报错 → Connector/J 自动回退客户端预处理

### 数据安全（极端环境）
- **不手动删锁**：实测 H2 AUTO_SERVER 会自动接管崩溃锁；手动删锁可能导致双进程写损坏
- `WRITE_DELAY=0`：提交强制刷盘
- `LOCK_TIMEOUT=15000` + 死锁重试
- **DbRecovery**：安全连接 + 完整性校验（坏表忽略、整库坏保护现场）+ 独立日志
  `data/recovery/[日期] h2db.log`

## 验证

| 客户端 | 内容 | 状态 |
|---|---|---|
| JDBC (Connector/J 8.4.0) | 认证 + 事务 + DDL + DML + 函数 + 方言 | ✅ 全绿 |
| HeidiSQL 12.21 | 真实密码认证 + 建库/建表/查询/数据浏览 | ✅ |

## 交付：两个 fat jar（用户各取所需）

| jar | Java 要求 | H2 | 数据库 |
|---|---|---|---|
| **mirage.jar** | Java **11+** | H2 2.x | `data/h2db` |
| **mirage-java8.jar** | Java **8** | H2 1.4.200 | `data/h2db-j8` |

**两者都是 fat jar**（H2 已打包进去），用户只需：
```
java -jar mirage.jar          # Java 11+ 环境
java -jar mirage-java8.jar    # Java 8 环境
```

无需任何 classpath 或额外下载。

### 为什么两个 jar？

- H2 2.x 是 **Java 11 字节码**，Java 8 无法加载 → Java 8 必须用 H2 1.4.200
- H2 1.4.200 与 2.x **数据库文件格式不兼容** → 各自独立库目录

`Db.java` 在**运行时探测**打包的 H2 版本（`org.h2.engine.Constants.FULL_VERSION`），自动选择库目录。

### 构建
```
build.bat     # 调 build.ps1，产出 mirage.jar + mirage-java8.jar
```

### 启动
```
run.bat       # 自动选 Java 11+（mirage.jar）或 Java 8（mirage-java8.jar）
```

---

## 数据保护与意外中断（已验证）

### 意外中断测试结果（强杀进程模拟断电）

| 场景 | 结果 |
|---|---|
| 写入中强杀（8 线程持续写） | 342400 行，seq **连续无空洞** |
| 已提交数据 | 强杀后完整保留 |
| 未提交数据 | 崩溃后自动回滚 |
| 崩溃残留锁 | H2 AUTO_SERVER 自动接管（**不手动删锁**） |
| 数据页损坏检测 | 分散采样命中坏页 |

### 完整性校验（启动时）

- 系统表可读 + CHECKPOINT
- 逐表**数据页扫描**：小表/≤2000 行全扫；大表分散采样（头/中/尾 5 点 × 200 行）
- 可 `-Dverify.full=true` 强制全表扫描

### 坏表处理策略（B 方案：警告但不禁用）

- 启动时检测损坏表 → **醒目警告横幅** + 记录到 `data/recovery/[日期] h2db.log`
- 坏表**不禁用**（用户可 DROP/ALTER/TRUNCATE 修复）
- 运行时访问坏表 → 错误信息附带明确提示：
  `[损坏表警告] 表 'X' 数据已损坏。此表仍可通过 DROP TABLE / ALTER TABLE 修复或删除；如需恢复数据请从备份。`

### 关键设计

- `WRITE_DELAY=0`：提交强制刷盘，断电最多丢未提交事务
- **不手动删锁**：信任 H2 锁机制（手动删锁可能导致双进程写损坏）
- `LOCK_TIMEOUT=15000` + 死锁重试
- 崩溃后自动恢复，无需人工干预

## 性能优化（已实施）

| 优化项 | 前 | 后 | 提升 |
|---|---|---|---|
| **日志瓶颈**（每条 SQL 都同步写文件+控制台） | 240 q/s | 2003 q/s | **+735%** |
| **结果集每包 flush** → 缓冲后一次写出 | — | — | 含在上项 |
| **@日志分级**：连接/认证/错误保留，SQL 默认关闭 | — | — | 关键 |
| **文件写入缓冲**（BufferedWriter, 非 autoflush） | — | — | 减少 IO |

### 性能基线（单机）

| 场景 | 吞吐 | 延迟 |
|---|---|---|
| Raw socket（纯协议） | ~2000 q/s | 499 us |
| JDBC 单连接 | ~1075 q/s | 930 us |
| JDBC 8 并发 | ~440-520 q/s | — |
| 对照：H2 直连 | 10590 q/s | 94 us |

### 日志开关（`mirage.properties`）
- `log.queries=true`：记录每条 SQL（默认关）
- `log.console=true`：同时输出到控制台（默认开）
- `log.file`：日志文件路径（默认 `./logs/mirage.log`）
- 连接/认证/错误日志**始终**写入 `log.file`（缓冲）

## 已知限制

- 仅 MySQL 8.x 协议
- **首次打开 H2 库可能耗时 20–30 秒**：`AUTO_SERVER=TRUE` 会启动内嵌 TCP 服务并做反向 DNS 解析
  （`InetAddress.getHostName()`），在无 DNS / 反向解析超时的网络环境下会等待系统超时。
  属于 H2 行为，不影响最终启动成功；如需提速可给 hosts 补一条本机 IP 映射。
- 存储过程/触发器/事件不落地（H2 无对应功能）
- 索引/外键元数据为简化返回
- 服务端预处理不支持（自动降级）

## 配置

首次启动若数据目录下没有 `mirage.properties`，会自动生成**带完整中文注释**的默认配置，
修改后重启生效。数据目录默认为启动时的工作目录（MC 插件则为 `plugins/Mirage/`），
配置里的相对路径都相对该目录解析。关键项：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.host` | `0.0.0.0` | 绑定地址（`127.0.0.1` = 仅本机） |
| `server.port` | `3306` | 监听端口 |
| `server.name` / `server.version` | `Mirage` / `8.4.0-mirage` | 客户端可见的服务器名/版本（握手包与 `SELECT VERSION()` 同源） |
| `server.backlog` / `server.maxConn` | `512` / `256` | 连接队列大小 / 最大并发连接数（最小 8） |
| `db.path` / `db.path.legacy` | `./data/h2db` / `./data/h2db-j8` | H2 库路径（按运行时 H2 版本自动二选一） |
| `db.user` / `db.password` | `sa` / 空 | H2 内嵌库凭据（内部用，与客户端登录无关；**已生效**，同时作用于主连接与每会话连接） |
| `log.level` | `INFO` | 最低输出级别：`DEBUG` / `INFO` / `WARN` / `ERROR`（同时作用于控制台与插件日志） |
| `auth.strict` | `true` | 严格校验客户端密码；`false` = 宽松模式（未知用户直接放行，仅调试用） |
| `db.autoServer` | `true` | 多进程共享库；无 DNS 环境下开启会让首次开库等待 20~30 秒，单进程可设 `false` |
| `auth.username` / `auth.password` | `root` / `root` | **客户端登录凭据（单账号）**；密码留空 = 免密。旧多账号格式已停用 |

> 默认账号为 `root / root`，**对外暴露前请修改 `auth.password`**，
> 并把 `server.host` 收紧到 `127.0.0.1` 或内网地址。
> 出于安全考虑仅支持单账号，`CREATE USER` / `DROP USER` 不再实际生效。

## 关闭流程（防丢数据 · 七个环境实机验证）

实测（客户端连接保持不关直接停服）：

| 环境 | 写入行数 | 关闭后校验 |
|---|---|---|
| Paper 1.21.4 + NMS | 11 | rows=11 ✅ |
| Velocity 4.3.1 | 9 | rows=9 ✅ |
| Paper 1.21.4 + mirage-nms.jar | 3 | rows=3 ✅ |
| **真实 Leaf 1.21.4 服务端 + mirage-nms.jar** | 22 | rows=22 ✅ |
| Paper 1.16.5 + Java 11 | 15 | rows=15 ✅ |
| **Folia 1.19.4 + Java 17** | 18 | rows=18 ✅ |
| **Folia 26.3 + Java 25** | 22 | rows=22 ✅ |



`MirageServer.stop()` 的顺序固定，不可调换：

1. 关闭 `ServerSocket`（不再接受新连接）
2. 主动断开所有客户端 socket（`FakeMysql.closeAllSockets`）
3. 等待连接线程池收敛（10 秒上限，超时强制中断）
4. 关闭所有会话的 H2 连接（`Session.closeAll`）
5. `CHECKPOINT` 落盘 + 关闭主 H2 连接（`Db.shutdown`）
6. 冲刷并关闭日志文件

独立运行时由 JVM shutdown hook 触发；MC 插件在 `onDisable` 中调用。
JVM 直接被杀的极端情况下，`Db` 自身的 shutdown hook 仍会尝试 CHECKPOINT 落盘。

## 目录

```
frontier/
├── src/mirage/core/     # 核心类（包 mirage.core）
├── lib/                 # h2-2.5.252 / h2-1.4.200 / mysql-connector-j
├── out11/ out8/         # 两套编译产物（构建生成）
├── build.bat/.ps1       # 构建 → mirage.jar + mirage-java8.jar
├── run.bat              # 自动降级启动
├── mirage.properties    # 配置（缺失时自动生成）
├── data/h2db.mv.db      # 数据库（运行时生成）
├── data/recovery/       # 恢复日志（运行时生成）
├── _debug_tests/        # 验证用测试类
└── README.md
```
