package mirage.core;

import java.sql.*;
import java.util.*;

/** 单个连接的会话状态，持有独立 H2 连接以隔离 schema。 */
public class Session {
    public final int connId;
    public String user = "root";
    public String currentDb = "h2test";
    public boolean autocommit = true;
    public long lastInsertId = 0;   // 本会话最后插入的自增 ID
    private Connection h2conn;

    /** 当前线程绑定的会话（每个连接一个线程，故可安全用 ThreadLocal）。 */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    public static void bind(Session s) { CURRENT.set(s); }
    public static Session current() { return CURRENT.get(); }
    public static void unbind() { CURRENT.remove(); }

    public Session(int connId) { this.connId = connId; }

    public synchronized Connection conn() throws SQLException {
        if (h2conn == null || h2conn.isClosed()) {
            h2conn = DriverManager.getConnection(Db.URL, Db.USER, Db.PASSWORD);
            try (Statement st = h2conn.createStatement()) {
                String s = h2Schema();
                if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) s = "\"" + s.replace("\"", "\"\"") + "\"";
                st.execute("SET SCHEMA " + s);
            }
        }
        return h2conn;
    }

    public void switchDb(String db) throws SQLException {
        this.currentDb = db;
        try (Statement st = conn().createStatement()) {
            String s = h2Schema();
            if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) s = "\"" + s.replace("\"", "\"\"") + "\"";
            st.execute("SET SCHEMA " + s);
        }
    }

    public String h2Schema(String db) {
        if (db == null) db = currentDb;
        String d = db.toLowerCase();
        if (d.equals("h2test") || d.equals("public")) return "PUBLIC";
        return db.toUpperCase();
    }
    public String h2Schema() { return h2Schema(currentDb); }

    /** 开始事务（关闭 autocommit）。 */
    public void beginTx() throws SQLException {
        conn().setAutoCommit(false);
        autocommit = false;
    }
    /** 提交当前事务（不改变 autocommit 模式，符合 MySQL 语义）。 */
    public void commitTx() throws SQLException {
        Connection c = conn();
        if (!c.getAutoCommit()) c.commit();
    }
    /** 回滚当前事务（不改变 autocommit 模式）。 */
    public void rollbackTx() throws SQLException {
        Connection c = conn();
        if (!c.getAutoCommit()) c.rollback();
    }
    /** 设置自动提交。 */
    public void setAutoCommit(boolean on) throws SQLException {
        conn().setAutoCommit(on);
        autocommit = on;
    }

    public void close() {
        try { if (h2conn != null && !h2conn.isClosed()) h2conn.close(); } catch (Exception ignore) {}
    }

    private static final Map<Integer, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    public static Session get(int connId) {
        return sessions.computeIfAbsent(connId, Session::new);
    }
    public static void remove(int connId) {
        Session s = sessions.remove(connId);
        if (s != null) s.close();
    }

    /** 当前活跃会话数（供关闭流程等待）。 */
    public static int activeCount() { return sessions.size(); }

    /**
     * 关闭所有会话的 H2 连接（服务端关闭时调用）。
     * 调用前客户端 socket 应已关闭，保证没有新 SQL 进来。
     */
    public static void closeAll() {
        for (Integer id : new ArrayList<>(sessions.keySet())) {
            Session s = sessions.remove(id);
            if (s != null) s.close();
        }
    }
}
