# Mirage — Fabric 模组（实验性）
# Ai生成

> ⚠️ **标记不稳定（Exptl）**。jar 命名：\`mirage-fabric-Exptl-<MC版本>.jar\`
> 例：\`mirage-fabric-Exptl-1.21.11.jar\`

## 状态

| 项 | 状态 |
|---|---|
| 源码骨架（fabric.mod.json + 入口类） | ✅ 已完成 |
| preLaunch 入口（最早执行、可阻塞） | ✅ 已定义 |
| 核心逻辑复用 mirage.core | ✅ |
| **构建（Gradle + Loom）** | ❌ **本机无法完成** |
| **实机运行验证** | ❌ **本机无法完成** |

## 为什么构建受阻

Fabric 模组**必须**用 **Gradle + Fabric Loom** 构建，且构建期需要：

1. Gradle（本机未安装）
2. Fabric Loom 插件（需联网拉取）
3. Minecraft 依赖（yarn mappings + minecraft jar，需联网）
4. Fabric Loader / Fabric API

本机网络无法访问 Maven Central / FabricMC / Mojang 仓库（实测多次下载均失败），
因此**只能交付源码骨架，无法产出可运行 jar**。

## 在有网络的构建机上完成

\`\`\`groovy
// build.gradle 关键片段
plugins {
    id 'fabric-loom' version '1.7-SNAPSHOT'
    id 'java'
}
dependencies {
    minecraft "com.mojang:minecraft:1.21.11"
    mappings "net.fabricmc:yarn:1.21.11+build.1:v2"
    modImplementation "net.fabricmc:fabric-loader:0.15.11"
    // 把 mirage.core 的 class 放进 src/main/resources 或作为本地 jar 依赖
    implementation files("../../../frontier/mirage.jar")
}
\`\`\`

然后 \`gradle build\`，产物重命名为 \`mirage-fabric-Exptl-1.21.11.jar\`。

## 版本策略

- **仅从 1.21.11 开始**（用户明确要求）
- **永远标记不稳定**
- 每适配一个新 MC 版本，出独立 jar（\`-Exptl-<版本>\`）
