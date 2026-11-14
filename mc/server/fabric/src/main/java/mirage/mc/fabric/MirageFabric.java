package mirage.mc.fabric;

import mirage.core.MirageConfig;
import mirage.core.MirageServer;

/**
 * Mirage Fabric 模组（仅 1.21.11+，实验性/不稳定）。
 *
 * Fabric 使用 preLaunch entrypoint 在游戏启动最早阶段执行，
 * 这正是"优先启动 + 阻塞等待"策略的最佳落点。
 *
 * 注意：本分支标记为 **Exptl（实验性）**，jar 命名为
 *       mirage-fabric-Exptl-<MC版本>.jar
 *
 * 构建依赖 Gradle + Fabric Loom，需在具备网络的构建机上执行：
 *   1) 准备 gradle 与 loom 配置（见 build.gradle 说明）
 *   2) gradle build
 */
public final class MirageFabric {
    private MirageFabric() {}

    /** 最早执行：游戏启动前。可在此阻塞，确保端口先就绪。 */
    public static void onPreLaunch() {
        startMirage("preLaunch");
    }

    /** 常规初始化。 */
    public static void onInitialize() {
        if (!MirageServer.isRunning()) {
            startMirage("initialize");
        }
    }

    private static void startMirage(String phase) {
        System.out.println("[Mirage] starting via " + phase + " entrypoint...");
        try {
            // Fabric 标准配置目录：config/mirage/
            java.io.File dataDir = new java.io.File("config", "mirage");
            if (!dataDir.exists()) dataDir.mkdirs();
            System.out.println("[Mirage] data folder: " + dataDir.getAbsolutePath());
            MirageConfig cfg = MirageConfig.load(dataDir);
            MirageServer.start(cfg);
            int waited = 0;
            while (!MirageServer.isRunning() && waited < 10000) {
                Thread.sleep(100);
                waited += 100;
            }
            System.out.println("[Mirage] MySQL proxy ready on " + cfg.host + ":" + cfg.port);
        } catch (Throwable t) {
            System.err.println("[Mirage] failed to start: " + t);
        }
    }
}
