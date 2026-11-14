# Mirage — 版本兼容性

> 返回 [README.md](README.md)

## Velocity 版本兼容

`mirage-velocity.jar` 使用 **Velocity 最稳定的那层 API**，理论上兼容 Velocity 全版本（1.0 ~ 4.x）。

只用到以下 API：

| API | 用途 | 1.x | 2.x | 3.x | 4.x |
|---|---|---|---|---|---|
| `@Plugin(id, name, version, description, authors)` | 插件元数据 | ✅ | ✅ | ✅ | ✅ |
| `@Subscribe` | 事件监听 | ✅ | ✅ | ✅ | ✅ |
| `ProxyInitializeEvent` / `ProxyShutdownEvent` | 生命周期 | ✅ | ✅ | ✅ | ✅ |
| `@DataDirectory Path` | 数据目录注入 | ✅ | ✅ | ✅ | ✅ |
| `ProxyServer` | 代理实例注入 | ✅ | ✅ | ✅ | ✅ |
| `Logger` (slf4j) | 日志 | ✅ | ✅ | ✅ | ✅ |

未使用调度器、命令 API、Adventure 文本、配置加载——这些才是 Velocity 版本间变化最大的部分。

### 已实测版本

| Velocity | Java | 结果 | 备注 |
|---|---|---|---|
| **1.0.10** | 25 | ✅ | 需加 `--add-opens java.base/java.lang=ALL-UNNAMED`（见下） |
| **3.1.0** | 25 | ✅ | 开箱即用 |
| **4.0.0** | 25 | ✅ | 开箱即用 |
| **4.3.1** | 25 | ✅ | 开箱即用 |

### 老版本（如 1.0.10）的启动必要参数

Velocity 1.0.x 自带的是老版 Guice + cglib，在 Java 9+ 的模块系统下做构造器注入代理时需要反射访问 `java.lang`。
不加参数会报：

```
java.lang.reflect.InaccessibleObjectException:
  Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(...)
  accessible: module java.base does not "opens java.lang" to unnamed module
```

启动时加参数即可：

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar velocity-1.0.10.jar
```

或直接用 **Java 11** 运行老版本 Velocity（无需 `--add-opens`）：

```bash
"C:\Program Files\Java\jdk-11\bin\java.exe" -jar velocity-1.0.10.jar
```

> 这是 Velocity 自身依赖的问题，与 Mirage 无关。任何使用 `@Inject` 构造注入的插件在 1.0.x + Java 9+ 环境下都需要这个参数。

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
