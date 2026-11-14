package mirage.core;

import java.sql.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 连接封装：持久性 + 死锁/锁冲突重试 + 安全恢复（委托 DbRecovery）。
 *
 * 极端环境（断电/崩溃/多进程）防数据丢失要点：
 *  - WRITE_DELAY=0      ：每次提交强制刷盘，断电最多丢"最后一次未提交事务"
 *  - LOCK_TIMEOUT=15000 ：锁等待 15s 后抛错，而非永久挂起（死锁可视）
 *  - AUTO_SERVER=TRUE   ：允许多进程共享
 *  - 重试              ：锁冲突/瞬时错误自动重试（指数退避）
 *  - 安全恢复          ：委托 DbRecovery（陈旧锁判断 + 备份 + 校验 + 保护现场）
 */
public class Db {
    static Connection conn;
    /** 当前 Java 主版本（8 / 17 / 21 / 25...）——仅用于日志/优化，不决定字节码。 */
    static final int JAVA_VERSION = detectJavaVersion();
    /** 探测到的 H2 完整版本号（供日志）。必须在 H2_LEGACY 之前初始化。 */
    static final String H2_VERSION = detectH2Version();
    /** 打包的 H2 版本（运行时探测）：1.x（旧，Java 8 兼容）或 2.x。 */
    static final boolean H2_LEGACY = detectH2Legacy();
    /** 运行时构建的连接 URL（init 时根据配置确定）。 */
    static String URL = null;
    /** 实际使用的库路径（供日志）。 */
    static String DB_PATH = null;
    /** H2 内嵌库凭据（来自 mirage.properties 的 db.user / db.password）。 */
    static String USER = "sa";
    static String PASSWORD = "";
    /** 是否启用 H2 AUTO_SERVER（多进程共享库）。 */
    static boolean AUTO_SERVER = true;
    /** 有序关闭只执行一次。 */
    private static volatile boolean closed = false;
    private static volatile boolean dbHookAdded = false;
    static final AtomicLong retryCount = new AtomicLong();

    static int detectJavaVersion() {
        String v = System.getProperty("java.specification.version");
        try {
            if (v.startsWith("1.")) return Integer.parseInt(v.substring(2));  // "1.8" → 8
            return Integer.parseInt(v);
        } catch (Exception e) { return 17; }
    }

    /** 探测打包的 H2 是否为 1.x（1.4.200，Java 8 兼容版）。
     *  通过 org.h2.engine.Constants.FULL_VERSION 判断（1.x 与 2.x 都有此类）。 */
    static String detectH2Version() {
        try {
            Class<?> c = Class.forName("org.h2.engine.Constants");
            Object v = c.getField("FULL_VERSION").get(null);
            return String.valueOf(v);
        } catch (Throwable e) {
            return "unknown";
        }
    }

    static boolean detectH2Legacy() {
        String v = H2_VERSION;
        return v.startsWith("1.") || v.equals("unknown");
    }

    /** 根据配置构建 H2 URL（路径来自 MirageConfig，支持相对/绝对路径）。 */
    static String buildUrl(String path) {
        if (path == null || path.trim().isEmpty()) path = H2_LEGACY ? "./data/h2db-j8" : "./data/h2db";
        path = path.replace("\\", "/");
        // 去掉可能已带的 jdbc:h2: 前缀，统一由这里拼
        if (path.startsWith("jdbc:h2:")) return path;
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:h2:file:").append(path).append(";MODE=MySQL");
        if (AUTO_SERVER) sb.append(";AUTO_SERVER=TRUE");
        sb.append(";WRITE_DELAY=0;LOCK_TIMEOUT=15000;DB_CLOSE_DELAY=-1;TRACE_LEVEL_FILE=0");
        if (!H2_LEGACY) sb.append(";RETENTION_TIME=0");
        return sb.toString();
    }

    /** 启动信息（供日志/横幅）。 */
    public static String envInfo() {
        return "Java " + System.getProperty("java.version")
             + " | H2 " + H2_VERSION + (H2_LEGACY ? " (legacy)" : "")
             + " | DB " + (DB_PATH != null ? DB_PATH : (H2_LEGACY ? "./data/h2db-j8" : "./data/h2db"));
    }

    static synchronized void init() throws Exception {
        Class.forName("org.h2.Driver");
        // 从配置解析库路径（未配置时用默认值）
        MirageConfig cfg = FakeMysql.CONFIG;
        String configured = null;
        if (cfg != null) configured = H2_LEGACY ? cfg.dbPathLegacy : cfg.dbPath;
        DB_PATH = (configured == null || configured.trim().isEmpty())
                ? (H2_LEGACY ? "./data/h2db-j8" : "./data/h2db") : configured;
        USER = (cfg != null && cfg.dbUser != null && !cfg.dbUser.trim().isEmpty()) ? cfg.dbUser.trim() : "sa";
        PASSWORD = (cfg != null && cfg.dbPassword != null) ? cfg.dbPassword : "";
        AUTO_SERVER = (cfg == null) || cfg.dbAutoServer;
        URL = buildUrl(DB_PATH);
        // 安全连接：由 DbRecovery 负责陈旧锁判断、备份、校验、保护现场
        conn = DbRecovery.safeConnect(URL);
        closed = false;
        // 兜底：JVM 直接退出（未走 MirageServer.stop）时也要把库安全关掉
        // 只注册一次，避免反复 start/stop 累积钩子
        if (!dbHookAdded) {
            dbHookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Db::shutdown, "Mirage-DbShutdown"));
        }
    }

    /**
     * 有序关闭数据库：先提交并 CHECKPOINT 落盘，再关闭主连接。
     * 调用前应确保所有客户端会话连接已关闭（见 MirageServer.stop）。
     */
    static synchronized void shutdown() {
        if (closed) return;
        closed = true;
        if (conn == null) return;
        try {
            if (!conn.isClosed()) {
                // 1) 把内存中的脏页刷入数据文件
                try (Statement st = conn.createStatement()) {
                    st.execute("CHECKPOINT");
                } catch (Exception e) {
                    LogSink.warn("[Db] CHECKPOINT 失败: " + e.getMessage());
                }
                // 2) 提交挂起事务（正常情况下无）
                try { conn.commit(); } catch (Exception ignore) {}
                // 3) 关闭连接，释放库锁
                conn.close();
                LogSink.info("[Db] 数据库已安全关闭");
            }
        } catch (Exception e) {
            LogSink.warn("[Db] 关闭数据库时出错: " + e.getMessage());
        } finally {
            conn = null;
        }
    }

    /** 带重试的执行包装：锁超时/瞬时错误自动重试。 */
    interface SqlAction<T> { T run() throws SQLException; }

    static <T> T withRetry(SqlAction<T> action) throws SQLException {
        int attempt = 0;
        long delay = 100;
        while (true) {
            try {
                return action.run();
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                boolean retryable = msg.contains("lock") || msg.contains("timeout")
                        || msg.contains("deadlock") || msg.contains("concurrent");
                attempt++;
                if (!retryable || attempt >= 10) throw e;
                retryCount.incrementAndGet();
                LogSink.warn("[Db] 可重试错误(第" + attempt + "次): " + e.getMessage());
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                delay = Math.min(delay * 2, 3000);
            }
        }
    }

    static Connection conn() { return conn; }
    public static long retries() { return retryCount.get(); }
}
