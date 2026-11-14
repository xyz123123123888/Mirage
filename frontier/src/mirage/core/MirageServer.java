package mirage.core;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirage 可嵌入服务器。
 * 与 FakeMysql.main()（阻塞式）不同：start() 立即返回，在后台线程接受连接。
 * MC 插件/模组用它把 Mirage 嵌入游戏进程。
 */
public class MirageServer {
    private static ServerSocket serverSocket;
    private static ExecutorService pool;
    private static volatile boolean running = false;
    private static Thread acceptThread;

    /** 启动（后台线程，立即返回）。cfg 为 null 时从 mirage.properties 加载。 */
    public static synchronized void start(MirageConfig cfg) throws Exception {
        if (running) { Console.warn("Mirage 已在运行"); return; }
        if (cfg == null) cfg = MirageConfig.load();
        FakeMysql.CONFIG = cfg;

        Console.toConsole = cfg.logConsole;
        Console.setLevel(cfg.logLevel);
        LogSink.setLevel(cfg.logLevel);
        FakeMysql.initLogFile(cfg.logFile);
        UserStore.loadFromConfig(cfg);
        Db.init();
        RsaAuth.init();
        SqlRouter.init();

        InetAddress bindAddr = InetAddress.getByName(cfg.host);
        serverSocket = new ServerSocket(cfg.port, cfg.backlog, bindAddr);
        int maxConn = cfg.maxConn > 0 ? Math.max(8, cfg.maxConn) : 256;
        pool = new ThreadPoolExecutor(
            8, maxConn, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> { Thread t = new Thread(r); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());
        running = true;

        Console.banner(cfg.serverName, cfg.version, cfg.port, cfg.host,
            Db.H2_LEGACY ? cfg.dbPathLegacy : cfg.dbPath, Db.H2_LEGACY);

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    int id = FakeMysql.connCounter.incrementAndGet();
                    pool.execute(() -> FakeMysql.handle(s, id));
                } catch (Exception e) {
                    if (running) FakeMysql.log("accept error: " + e);
                }
            }
        }, "Mirage-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        // 独立运行时没有插件 onDisable，用 JVM 关闭钩子走同一套有序关闭流程
        Runtime.getRuntime().addShutdownHook(new Thread(MirageServer::stop, "Mirage-StopHook"));
        Console.info("Mirage 已启动，监听 " + cfg.host + ":" + cfg.port);
    }

    /**
     * 有序停止服务器。顺序不可调换：
     *  1. 停止接受新连接（关 ServerSocket）
     *  2. 主动断开所有客户端连接（让处理线程立刻退出，不再产生新 SQL）
     *  3. 等待处理线程池收敛（有上限，避免卡死）
     *  4. 关闭所有会话的 H2 连接（提交/回滚未完成事务）
     *  5. CHECKPOINT + 关闭主 H2 连接（落盘、释放库锁）
     *  6. 冲刷并关闭日志文件
     */
    public static synchronized void stop() {
        if (!running) return;
        running = false;

        // 1) 不再接受新连接
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignore) {}
        serverSocket = null;

        // 2) 主动断开客户端
        try { FakeMysql.closeAllSockets(); } catch (Exception e) { Console.warn("[shutdown] 断开客户端出错: " + e.getMessage()); }

        // 3) 等待处理线程退出
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    Console.warn("[shutdown] 仍有连接线程未退出，强制中断");
                    pool.shutdownNow();
                    pool.awaitTermination(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
            pool = null;
        }

        // 4) 关闭所有会话连接
        try { Session.closeAll(); } catch (Exception e) { Console.warn("[shutdown] 关闭会话出错: " + e.getMessage()); }

        // 5) 落盘并关闭 H2
        try { Db.shutdown(); } catch (Exception e) { Console.warn("[shutdown] 关闭数据库出错: " + e.getMessage()); }

        // 6) 日志收尾
        try { FakeMysql.closeLogFile(); } catch (Exception ignore) {}

        Console.info("Mirage 已安全停止");
    }

    public static boolean isRunning() { return running; }
}
