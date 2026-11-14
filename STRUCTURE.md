# Mirage — 项目结构与构建

> 返回 [README.md](README.md)

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
| **NMS 多版本** | 兼容 Bukkit / Spigot / Paper / Leaf；**字节码 Java 11**。Folia 支持已不再维护（检测到时打警告） | `mirage-nms.jar` | 已交付 |
| **Velocity 代理** | 理论上兼容 Velocity 全版本（1.0 ~ 4.x）；字节码 Java 11 | `mirage-velocity.jar` | 已交付 |
| **Folia** | 区域化多线程分支，需 `folia-supported: true`；**唯一维护 Folia 的产物** | `mirage-folia.jar` | 已交付 |
| **Fabric** | 仅 1.21.11+（标记不稳定） | `mirage-fabric-Exptl-<MC版本>.jar` | 源码就绪，构建受阻 |

**前沿独立** 的 jar 直接命名 `mirage.jar`（无平台后缀）。

> **Folia 特别说明**：`mirage-nms.jar` 在 `plugin.yml` 中声明了 `folia-supported: true`。
> 这个声明的含义是「本插件不会调用 Folia 不安全的 API 而崩溃」——Mirage 不碰任何 MC API
>（世界/实体/区块/调度器），确实满足这个条件，在 Folia 上能正常工作。
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
>
> ```
> -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
> ```

## 构建结构（NMS + Leaf 共项目，Folia 独立）

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

## jar 命名规则

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

## 启动策略（参考 Geyser）

- 插件/模组**优先以最高 API 顺序启动**
- 随后**故意阻塞启动**——防止绑定我们的插件/模组（如反作弊）先加载而因无 MySQL 连接崩溃
- MC 环境**不做单独版本兼容**（除 Fabric），直接用最简单方法跑 1.8+
- 可复用 API 少 → **自研 API 防崩溃**
