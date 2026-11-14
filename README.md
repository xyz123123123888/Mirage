# Mirage

> **A MySQL-compatible proxy backed by H2.**

客户端以为连的是 MySQL，实际上后端是 H2——正如沙漠中的海市蜃楼。

**中文** · [English](en/README.md)

## 文档导航

| 文档 | 内容 |
|---|---|
| [STRUCTURE.md](STRUCTURE.md) | 项目结构、两个独立项目、MC 分支版本策略、构建结构、jar 命名规则、启动策略 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Velocity 版本兼容（1.0 ~ 4.x）、Java 版本兼容（11 ~ 26） |
| [TESTING.md](TESTING.md) | 交付状态说明、各平台实测日志、实机验证矩阵 |
| [CONFIG.md](CONFIG.md) | 文件位置（标准目录）、配置项、关闭流程 |
| [LICENSE.md](LICENSE.md) | Apache-2.0 许可证说明、第三方组件、无 GPL 传染 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构总览 |
| [frontier/README.md](frontier/README.md) | 前沿独立项目详细文档 |
| [mc/server/nms/README.md](mc/server/nms/README.md) | NMS 插件详细文档 |
| [mc/server/fabric/README.md](mc/server/fabric/README.md) | Fabric 源码骨架说明 |

## 一句话概览

- **是什么**：MySQL 协议代理，后端用 H2 存储。零 MySQL 代码，无 GPL 传染。
- **两种运行方式**：
  1. **独立运行**：`java -jar mirage.jar`（Java 11+）或 `java -jar mirage-java8.jar`（Java 8）
  2. **作为 Minecraft 插件**：NMS（内置 Leaf 优化）、Folia、Velocity 三个产物
- **交付 5 个 jar**：

  | jar | 平台 | 字节码 |
  |---|---|---|
  | `mirage.jar` | 前沿独立（H2 2.x） | major 55（Java 11+） |
  | `mirage-java8.jar` | 前沿独立（H2 1.4.200） | major 52（Java 8+） |
  | `mirage-nms.jar` | Bukkit / Spigot / Paper / Leaf（内置 Leaf 优化） | major 55 |
  | `mirage-folia.jar` | Folia（区域化多线程分支） | major 55 |
  | `mirage-velocity.jar` | Velocity 代理（1.0 ~ 4.x） | major 55 |

- **配置**：首次启动在数据目录自动生成带完整中文注释的 `mirage.properties`，详见 [CONFIG.md](CONFIG.md)
- **许可证**：Apache-2.0，详见 [LICENSE.md](LICENSE.md)

## 快速开始（前沿独立）

```powershell
cd frontier
build.bat    # 生成 out11 / out8
run.bat      # 检测 java -version，自动选 mirage.jar 或 mirage-java8.jar
```

或直接用现成的 jar：

```bash
java -jar mirage.jar          # Java 11+
java -jar mirage-java8.jar    # Java 8
```

## 快速开始（Minecraft 插件）

1. 选择对应平台的 jar（NMS / Folia / Velocity）
2. 放入服务端 `plugins/` 目录
3. 启动服务端，首次启动会在 `plugins/<插件名>/` 下自动生成配置文件
4. 客户端用 `root / root` 连接 `localhost:3306`（**对外暴露前务必改密码**）

详细步骤见 [STRUCTURE.md](STRUCTURE.md) 与各平台 README。
