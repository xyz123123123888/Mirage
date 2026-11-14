# Mirage
Mirage 是一个用 Java 编写的 MySQL 协议代理。客户端以为自己连的是 MySQL，实际上数据存放在内嵌的 H2 数据库里——正如沙漠中的海市蜃楼。项目不包含任何 MySQL 代码，也没有 GPL 传染问题。它既可以作为独立服务（java -jar mirage.jar）运行，也可以作为插件嵌入 Minecraft 服务端（Bukkit / Spigot / Paper / Leaf / Folia / Velocity），让依赖 MySQL 的插件无需改动就能工作。整个应用只需一个 jar 文件部署，字节码目标为 Java 11，可在 Java 11 ~ 26 上直接运行。
