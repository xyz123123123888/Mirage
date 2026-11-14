package mirage.mc.folia;

import org.bukkit.plugin.java.JavaPlugin;

import mirage.core.LogSink;
import mirage.core.MirageConfig;
import mirage.core.MirageServer;

/**
 * Mirage Folia 插件（Folia = Paper 的区域化多线程分支）。
 *
 * Folia 与 Paper 的核心差异：
 *  - 区域化多线程：世界被划分为多个区域，各区域独立 tick
 *  - 禁止使用 BukkitScheduler（Bukkit.getScheduler()），必须用 Folia 的：
 *      GlobalRegionScheduler / RegionScheduler / AsyncScheduler / EntityScheduler
 *  - 插件必须在 plugin.yml 声明 folia-supported: true，否则拒绝加载
 *
 * Mirage 的线程模型天然兼容 Folia：
 *  - 本插件不触碰任何世界/实体/区块 API
 *  - MySQL 代理跑在自己的 ThreadPoolExecutor 里，只访问 H2/JDBC
 *  - onEnable / onDisable 在 Folia 的全局区域线程上执行，不需要额外调度
 * 因此这里与 NMS 版本逻辑一致，只需 folia-supported 元数据与独立包名。
 */
public class MirageFoliaPlugin extends JavaPlugin {
    private static MirageFoliaPlugin instance;

    @Override
    public void onLoad() {
        instance = this;
        LogSink.setHandler(new LogSink.Handler() {
            public void info(String msg) { getLogger().info(msg); }
            public void warn(String msg) { getLogger().warning(msg); }
            public void error(String msg) { getLogger().severe(msg); }
        });
        getLogger().info("Mirage (Folia) loading...");
    }

    @Override
    public void onEnable() {
        getLogger().info("Mirage (Folia) enabling, starting MySQL proxy...");
        try {
            java.io.File dataDir = getDataFolder();
            if (!dataDir.exists()) dataDir.mkdirs();
            getLogger().info("Mirage data folder: " + dataDir.getAbsolutePath());
            MirageConfig cfg = MirageConfig.load(dataDir);
            MirageServer.start(cfg);
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
     * Folia 的 onDisable 在全局区域线程执行，MirageServer.stop() 内部会等待自己的线程池收敛，
     * 不依赖任何 Bukkit 调度器，因此不会触发 Folia 的线程检查。
     */
    @Override
    public void onDisable() {
        getLogger().info("Mirage (Folia) disabling: closing connections and flushing database...");
        try {
            MirageServer.stop();
            getLogger().info("Mirage (Folia) stopped safely (data flushed).");
        } catch (Throwable t) {
            getLogger().severe("Mirage stop error: " + t);
        }
    }

    public static MirageFoliaPlugin getInstance() { return instance; }
}
