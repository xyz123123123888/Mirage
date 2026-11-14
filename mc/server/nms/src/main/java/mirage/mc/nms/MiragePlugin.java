package mirage.mc.nms;

import org.bukkit.plugin.java.JavaPlugin;

import mirage.core.LogSink;
import mirage.core.MirageConfig;
import mirage.core.MirageServer;

/**
 * Mirage NMS 插件（Bukkit / Spigot / Paper 等同源服务端通用）。
 *
 * 启动策略（参考 Geyser）：
 *  1. plugin.yml 声明 load: STARTUP —— 尽可能早加载
 *  2. onLoad 中提前初始化，确保依赖 Mirage 的插件（如反作弊）加载时 MySQL 端口已就绪
 *  3. onEnable 启动 MirageServer（后台线程，非阻塞）
 *  4. onDisable 优雅停止
 *
 * 注：核心类已移入 mirage.core 包，可直接 import，无需反射。
 *
 * Folia 支持状态：**NMS 内置的 Folia 支持已不再维护**。
 *
 * 语义澄清：
 *  - plugin.yml 声明 folia-supported: true，意为「本插件不会调用 Folia 不安全的 API 而崩溃」
 *    ——Mirage 不碰任何 MC API（世界/实体/区块/调度器），确实满足
 *  - 但 NMS 内置的 Folia 兼容层不再投入维护：Folia 的线程模型与 Paper 差异较大，
 *    每次 Folia 更新都可能需要同步修改 NMS 的兼容层，维护成本高
 *  - 已另有独立项目 mc/server/folia/ 专门跟进 Folia，建议 Folia 用户直接使用 mirage-folia.jar
 *
 * 本插件在检测到 Folia 时会打出醒目警告，说明上述情况并给出改用指引。
 */
public class MiragePlugin extends JavaPlugin {
    private static MiragePlugin instance;

    /**
     * 检测当前服务端是否为 Folia。
     * Folia 会提供 io.papermc.paper.threadedregions.RegionizedServerInitEvent 类，
     * Paper / Bukkit / Spigot / Leaf 都没有这个类，因此用它作为判定依据最可靠。
     * （Folia 1.19.4 与 26.3 两个版本都有此类）
     */
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServerInitEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onLoad() {
        instance = this;
        // 把核心日志接到插件 logger，避免 System.out/err 被服务端标为 ERROR
        LogSink.setHandler(new LogSink.Handler() {
            public void info(String msg) { getLogger().info(msg); }
            public void warn(String msg) { getLogger().warning(msg); }
            public void error(String msg) { getLogger().severe(msg); }
        });
        getLogger().info("Mirage loading (STARTUP priority)...");

        if (isFolia()) {
            getLogger().warning("============================================================");
            getLogger().warning("  [危险] 检测到当前服务端是 Folia！");
            getLogger().warning("");
            getLogger().warning("  mirage-nms.jar 内置的 Folia 支持已不再维护。");
            getLogger().warning("  Folia 的线程模型与 Paper 差异较大，维护成本高；");
            getLogger().warning("  Mirage 已另有独立项目专门跟进 Folia。");
            getLogger().warning("");
            getLogger().warning("  请改用 mirage-folia.jar（Folia 专用版本）：");
            getLogger().warning("    1. 删除 plugins/mirage-nms.jar");
            getLogger().warning("    2. 放入 plugins/mirage-folia.jar");
            getLogger().warning("    3. 重启服务端");
            getLogger().warning("");
            getLogger().warning("  继续使用 mirage-nms.jar 可能工作，但不保证、不维护、");
            getLogger().warning("  随时可能因 Folia 更新而失效。");
            getLogger().warning("============================================================");
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("Mirage enabling, starting MySQL proxy...");
        try {
            // 标准插件数据目录：plugins/Mirage/ —— 配置、数据库、日志都在这里
            java.io.File dataDir = getDataFolder();
            if (!dataDir.exists()) dataDir.mkdirs();
            getLogger().info("Mirage data folder: " + dataDir.getAbsolutePath());
            MirageConfig cfg = MirageConfig.load(dataDir);
            MirageServer.start(cfg);
            // 等待端口就绪（最多 10 秒），确保后续插件能连上
            int waited = 0;
            while (!MirageServer.isRunning() && waited < 10000) {
                Thread.sleep(100);
                waited += 100;
            }
            if (MirageServer.isRunning()) {
                getLogger().info("Mirage MySQL proxy ready on " + cfg.host + ":" + cfg.port);
            } else {
                getLogger().warning("Mirage started but port not confirmed ready in 10s.");
            }
        } catch (Throwable t) {
            getLogger().severe("Mirage failed to start: " + t);
        }
    }

    /**
     * 关闭流程（顺序固定，确保不丢数据）：
     *   主动断开所有客户端 -> 等待连接线程退出 -> 关闭会话连接
     *   -> CHECKPOINT 落盘并关闭 H2 -> 关闭日志 -> 插件卸载
     * 插件作为最后退出者，保证依赖它的插件先停止时数据仍可写。
     */
    @Override
    public void onDisable() {
        getLogger().info("Mirage disabling: closing connections and flushing database...");
        try {
            MirageServer.stop();
            getLogger().info("Mirage stopped safely (data flushed).");
        } catch (Throwable t) {
            getLogger().severe("Mirage stop error: " + t);
        }
    }

    public static MiragePlugin getInstance() { return instance; }
}
