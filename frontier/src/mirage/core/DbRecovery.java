package mirage.core;

import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 数据库安全连接 + 完整性校验 + 独立日志。
 *
 * 关键认知（经实测）：
 *  - H2 的 AUTO_SERVER 模式**会自动接管崩溃残留的锁**（等待 LOCK_TIMEOUT 后）。
 *  - 因此**绝不手动删锁**：手动删锁可能在"活进程持有锁"时导致双进程写同一文件 → 数据损坏。
 *  - AUTO_SERVER 下真正的多进程是"共享 server"，不产生锁冲突。
 *  - H2 报 "may be already in use" 时，要么是崩溃锁（H2 会自接管），要么是真有活进程 → 都不能删锁。
 *
 * 本类只做三件事：
 *  1. 连接（信任 H2 的锁机制）
 *  2. 完整性校验（坏表忽略，整库坏保护现场）
 *  3. 全程写独立日志，出错不崩溃式降级
 */
public class DbRecovery {
    static final String RECOVERY_DIR = "./data/recovery";
    static final String DB_NAME = "h2db";

    static PrintWriter log;
    static final Object logLock = new Object();

    static void initLog() {
        if (log != null) return;
        new File(RECOVERY_DIR).mkdirs();
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        String file = RECOVERY_DIR + "/" + date + " " + DB_NAME + ".log";
        try { log = new PrintWriter(new FileWriter(file, true), true); }
        catch (IOException e) { LogSink.error("[DbRecovery] 无法创建恢复日志: " + e.getMessage()); }
    }

    static void logLine(String level, String msg) {
        String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date()) + "] [" + level + "] " + msg;
        synchronized (logLock) {
            // 走 LogSink：MC 插件注入 plugin.getLogger()，独立运行回退 System.out/err
            if ("ERROR".equals(level)) LogSink.error(msg);
            else if ("WARN".equals(level)) LogSink.warn(msg);
            else LogSink.info(msg);
            if (log != null) log.println(line);
        }
    }
    static void info(String m) { logLine("INFO", m); }
    static void warn(String m) { logLine("WARN", m); }
    static void error(String m) { logLine("ERROR", m); }

    /** 安全连接：信任 H2 锁机制 + 完整性校验 + 独立日志。 */
    static Connection safeConnect(String url) throws SQLException {
        initLog();
        info("=== 启动数据库连接 ===");
        Connection c;
        try {
            c = DriverManager.getConnection(url, Db.USER, Db.PASSWORD);
            info("连接成功");
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("already in use") || msg.contains("lock") || msg.contains("正在使用")) {
                error("数据库被占用: " + e.getMessage());
                error("这可能是崩溃残留锁（H2 会自动接管），或真的有其他进程在运行。");
                error("保护现场：不进行任何解锁操作（手动删锁会导致双进程写同一文件 → 数据损坏）。");
                error("建议：确认无其他进程后稍等重试；若持续失败请人工检查。");
            } else {
                error("连接失败（可能数据库文件损坏或格式错误）: " + e.getMessage());
                error("保护现场：不进行任何操作。");
            }
            throw e;
        }
        if (!verifyIntegrity(c)) {
            error("数据库完整性校验失败，判定为损坏");
            try { c.close(); } catch (Exception ignore) {}
            throw new SQLException("数据库损坏，已保护现场，请人工检查数据库文件");
        }
        info("=== 数据库连接就绪 ===");
        return c;
    }

    /** 坏表清单（启动校验发现的损坏表，供运行时告警）。 */
    static final java.util.Set<String> BROKEN_TABLES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 判断某表（忽略大小写、schema）是否已知损坏。 */
    public static boolean isBroken(String tableName) {
        if (tableName == null) return false;
        String t = tableName.toLowerCase();
        // 去掉 schema 前缀
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        t = t.replace("\"", "").replace("\u0060", "").replace("`", "");
        return BROKEN_TABLES.contains(t);
    }

    /** 强制全表扫描（-Dverify.full=true）。 */
    static final boolean VERIFY_FULL = Boolean.getBoolean("verify.full");
    /** 表行数超过此阈值才启用分散采样，否则全扫。 */
    static final long VERIFY_FULL_THRESHOLD = 2000;
    /** 大表分散采样的抽样点数（头/中/尾多处）。 */
    static final int VERIFY_SAMPLE_POINTS = 5;
    /** 每个采样点扫描的行数。 */
    static final int VERIFY_SAMPLE_ROWS = 200;

    /** 完整性校验：系统表 + CHECKPOINT + 逐表元数据 + 数据页扫描（坏表警告忽略，整库坏拒绝）。 */
    static boolean verifyIntegrity(Connection c) {
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES")) {
                rs.next();
                info("校验：系统表可读，共 " + rs.getInt(1) + " 张表");
            }
            st.execute("CHECKPOINT");
            info("校验：CHECKPOINT 成功");
            int broken = 0, total = 0;
            List<String[]> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA')")) {
                while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            for (String[] t : tables) {
                total++;
                String full = "\"" + t[0] + "\".\"" + t[1] + "\"";
                try (Statement st2 = c.createStatement()) {
                    // 1) 元数据检查
                    st2.executeQuery("SELECT * FROM " + full + " WHERE 1=0").close();
                    // 2) 估算行数
                    long rowCount = 0;
                    try (ResultSet rs = st2.executeQuery("SELECT COUNT(*) FROM " + full)) { rs.next(); rowCount = rs.getLong(1); }
                    // 3) 数据扫描：小表/强制全扫 → 全表；大表 → 分散采样（头/中/尾）
                    if (VERIFY_FULL || rowCount <= VERIFY_FULL_THRESHOLD) {
                        long rows = 0;
                        try (ResultSet rs = st2.executeQuery("SELECT * FROM " + full)) { while (rs.next()) rows++; }
                        info("表 " + t[0] + "." + t[1] + " 全表扫描 " + rows + " 行 OK");
                    } else {
                        // 分散采样：OFFSET 0, 1/4, 1/2, 3/4, 末尾
                        long scanned = 0;
                        for (int p = 0; p < VERIFY_SAMPLE_POINTS; p++) {
                            long offset = (rowCount / VERIFY_SAMPLE_POINTS) * p;
                            String sql = "SELECT * FROM " + full + " LIMIT " + VERIFY_SAMPLE_ROWS + " OFFSET " + offset;
                            try (ResultSet rs = st2.executeQuery(sql)) { while (rs.next()) scanned++; }
                        }
                        info("表 " + t[0] + "." + t[1] + " 分散采样 " + scanned + " 行（共 " + rowCount + " 行，" + VERIFY_SAMPLE_POINTS + " 个采样点）OK");
                    }
                } catch (Exception te) {
                    warn("表 " + t[0] + "." + t[1] + " 校验失败（忽略，属用户数据）: " + te.getMessage());
                    BROKEN_TABLES.add(t[1].toLowerCase());
                    broken++;
                }
            }
            if (broken > 0) {
                info("校验完成：共 " + total + " 表，" + broken + " 张异常（已忽略，不影响整体）");
                info("⚠️ 损坏表清单（可 DROP 或从备份恢复）: " + BROKEN_TABLES);
                warn("========================================");
                warn("⚠️  警告：检测到 " + broken + " 张损坏表");
                warn("    " + BROKEN_TABLES);
                warn("    这些表仍可 DROP/ALTER，但数据读取可能报错。");
                warn("========================================");
            } else {
                info("校验完成：共 " + total + " 表，全部正常（含数据页扫描）");
            }
            return true;
        } catch (Exception e) {
            error("校验过程异常: " + e.getMessage());
            return false;
        }
    }
}
