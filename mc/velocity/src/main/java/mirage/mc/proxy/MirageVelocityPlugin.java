package mirage.mc.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

import mirage.core.LogSink;
import mirage.core.MirageConfig;
import mirage.core.MirageServer;

/**
 * Mirage Velocity 插件（仅代理端）。
 *
 * Velocity 的插件加载由 @Plugin 依赖图拓扑排序控制，
 * 其他插件可声明 dependency: mirage 以保证加载顺序。
 * 核心逻辑与 NMS/Leaf 一致：初始化时启动，关闭时停止。
 */
@Plugin(
    id = "mirage",
    name = "Mirage",
    version = "8.4.0-mirage",
    description = "A MySQL-compatible proxy backed by H2.",
    authors = {"Mirage"}
)
public class MirageVelocityPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public MirageVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        LogSink.setHandler(new LogSink.Handler() {
            public void info(String msg) { logger.info(msg); }
            public void warn(String msg) { logger.warn(msg); }
            public void error(String msg) { logger.error(msg); }
        });
        logger.info("Mirage (Velocity) enabling, starting MySQL proxy...");
        try {
            // 标准插件数据目录：plugins/mirage/ —— 配置、数据库、日志都在这里
            java.io.File dataDir = dataDirectory.toFile();
            if (!dataDir.exists()) dataDir.mkdirs();
            logger.info("Mirage data folder: {}", dataDir.getAbsolutePath());
            MirageConfig cfg = MirageConfig.load(dataDir);
            MirageServer.start(cfg);
            int waited = 0;
            while (!MirageServer.isRunning() && waited < 10000) {
                Thread.sleep(100);
                waited += 100;
            }
            if (MirageServer.isRunning()) {
                logger.info("Mirage MySQL proxy ready on {}:{}", cfg.host, cfg.port);
            } else {
                logger.warn("Mirage started but port not confirmed ready in 10s.");
            }
        } catch (Throwable t) {
            logger.error("Mirage failed to start", t);
        }
    }

    /**
     * 关闭流程（顺序固定，确保不丢数据）：
     *   主动断开所有客户端 -> 等待连接线程退出 -> 关闭会话连接
     *   -> CHECKPOINT 落盘并关闭 H2 -> 关闭日志 -> 代理卸载
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Mirage (Velocity) disabling: closing connections and flushing database...");
        try {
            MirageServer.stop();
            logger.info("Mirage (Velocity) stopped safely (data flushed).");
        } catch (Throwable t) {
            logger.error("Mirage stop error", t);
        }
    }
}
