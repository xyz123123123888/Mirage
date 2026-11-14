package mirage.core;

import java.io.*;
import java.util.*;

/**
 * Mirage 配置：从 mirage.properties 加载，缺失时自动生成带注释的默认配置。
 *
 * 路径规则：
 *  - 配置文件名固定为 mirage.properties
 *  - 若通过 {@link #load(File)} 传入数据目录（MC 插件传入 plugins/Mirage），
 *    则配置、数据库、日志全部以该目录为基准；配置里写相对路径即可
 *  - 独立运行时数据目录为当前工作目录
 */
public class MirageConfig {
    // ---- 服务器 ----
    public String host = "0.0.0.0";
    public int port = 3306;
    public String serverName = "Mirage";
    public String version = "8.4.0-mirage";
    public int backlog = 512;
    /** 最大并发连接数（线程池上限，最小 8）。 */
    public int maxConn = 256;

    // ---- 数据库 ----
    public String dbPath = "./data/h2db";          // Java 11+ / H2 2.x
    public String dbPathLegacy = "./data/h2db-j8"; // Java 8 / H2 1.4.200
    public String dbUser = "sa";
    public String dbPassword = "";
    /** AUTO_SERVER=TRUE 允许多进程共享库，但启动时会做反向 DNS 解析（无 DNS 环境可能慢 20~30s）。 */
    public boolean dbAutoServer = true;

    // ---- 日志 ----
    public boolean logQueries = false;
    public boolean logConsole = true;
    public String logFile = "./logs/mirage.log";
    /** 最低输出级别：DEBUG / INFO / WARN / ERROR。 */
    public String logLevel = "INFO";

    // ---- 认证 ----
    /** true = 严格校验客户端密码；false = 宽松模式（未知用户直接放行，仅调试用）。 */
    public boolean authStrict = true;

    // ---- 用户 ----
    /** 客户端登录凭据：username -> password */
    public final LinkedHashMap<String, String> users = new LinkedHashMap<>();

    // ---- 运行时（不写入配置）----
    /** 数据目录（配置/数据库/日志的基准目录）。 */
    public transient File baseDir;
    /** 实际使用的配置文件。 */
    private transient File configFile;

    private static final String FILE_NAME = "mirage.properties";

    /** 以当前工作目录为数据目录加载配置。 */
    public static MirageConfig load() { return load(null); }

    /**
     * 以指定数据目录加载配置（目录不存在会自动创建）。
     * @param dataDir 数据目录；null 表示当前工作目录
     */
    public static MirageConfig load(File dataDir) {
        MirageConfig c = new MirageConfig();
        File base = (dataDir == null) ? new File(".") : dataDir;
        if (!base.exists()) base.mkdirs();
        c.baseDir = base;
        c.configFile = new File(base, FILE_NAME);

        if (!c.configFile.exists()) {
            c.defaultUsers();
            c.save();
            c.resolvePaths();
            return c;
        }

        Properties p = new Properties();
        try (InputStream in = new FileInputStream(c.configFile)) {
            p.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogSink.error("[MirageConfig] 读取配置失败，使用默认: " + e.getMessage());
            c.defaultUsers();
            c.resolvePaths();
            return c;
        }

        c.host = p.getProperty("server.host", c.host);
        c.port = parseInt(p.getProperty("server.port"), c.port);
        c.serverName = p.getProperty("server.name", c.serverName);
        c.version = p.getProperty("server.version", c.version);
        c.backlog = parseInt(p.getProperty("server.backlog"), c.backlog);
        c.maxConn = Math.max(8, parseInt(p.getProperty("server.maxConn"), c.maxConn));

        c.dbPath = p.getProperty("db.path", c.dbPath);
        c.dbPathLegacy = p.getProperty("db.path.legacy", c.dbPathLegacy);
        c.dbUser = p.getProperty("db.user", c.dbUser);
        c.dbPassword = p.getProperty("db.password", c.dbPassword);
        c.dbAutoServer = Boolean.parseBoolean(p.getProperty("db.autoServer", "true"));

        c.logQueries = Boolean.parseBoolean(p.getProperty("log.queries", "false"));
        c.logConsole = Boolean.parseBoolean(p.getProperty("log.console", "true"));
        c.logFile = p.getProperty("log.file", c.logFile);
        c.logLevel = p.getProperty("log.level", c.logLevel);

        c.authStrict = Boolean.parseBoolean(p.getProperty("auth.strict", "true"));

        // 认证账号：出于安全考虑，仅支持单个账号。
        String uname = p.getProperty("auth.username", "").trim();
        String upass = p.getProperty("auth.password", "");
        if (!uname.isEmpty()) {
            c.users.clear();
            c.users.put(uname, upass);
        } else {
            c.defaultUsers();
        }

        // ---- 以下为旧的多账号格式，已停用（保留以便回滚）----
        // 格式一：user.<用户名>=<密码>，每行一个
        // for (String key : p.stringPropertyNames()) {
        //     if (key.startsWith("user.") && key.length() > 5) {
        //         String name = key.substring(5).trim();
        //         if (!name.isEmpty()) c.users.put(name, p.getProperty(key, ""));
        //     }
        // }
        // 格式二：users=name:pass,name:pass
        // String legacy = p.getProperty("users", "");
        // if (!legacy.trim().isEmpty()) {
        //     for (String pair : legacy.split(",")) {
        //         int idx = pair.indexOf(':');
        //         if (idx > 0) c.users.put(pair.substring(0, idx).trim(), pair.substring(idx + 1));
        //     }
        // }

        c.resolvePaths();
        return c;
    }

    /** 把配置中的相对路径解析为基于数据目录的绝对路径。 */
    private void resolvePaths() {
        dbPath = resolve(dbPath);
        dbPathLegacy = resolve(dbPathLegacy);
        logFile = resolve(logFile);
    }

    private String resolve(String p) {
        if (p == null || p.trim().isEmpty()) return p;
        String s = p.trim().replace("\\", "/");
        if (s.startsWith("jdbc:h2:")) return s;
        File f = new File(s);
        if (f.isAbsolute()) return s;
        // 去掉开头的 ./ 或 .\，避免拼出 /././data 这类路径
        while (s.startsWith("./")) s = s.substring(2);
        if (s.isEmpty()) s = ".";
        try {
            return new File(baseDir, s).getCanonicalPath().replace("\\", "/");
        } catch (Exception e) {
            return new File(baseDir, s).getAbsolutePath().replace("\\", "/");
        }
    }

    /** 默认账号 root / root；对外暴露前务必改密码。 */
    public void defaultUsers() {
        users.clear();
        users.put("root", "root");
    }

    /** 把当前配置写回 mirage.properties（带完整注释）。 */
    public void save() {
        if (configFile == null) configFile = new File(baseDir == null ? new File(".") : baseDir, FILE_NAME);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# Mirage 配置文件（自动生成，可自由修改）\n");
        sb.append("#\n");
        sb.append("# 本文件所在目录即为数据目录，所有相对路径都相对它解析。\n");
        sb.append("# 修改后需重启服务端或程序才生效。\n");
        sb.append("# ============================================================\n\n");

        sb.append("# ===== 服务器 =====\n");
        sb.append("# 绑定地址：0.0.0.0 = 所有网卡；127.0.0.1 = 仅本机（更安全）\n");
        sb.append("server.host=").append(host).append("\n");
        sb.append("# 监听端口（客户端连接的 MySQL 端口）\n");
        sb.append("server.port=").append(port).append("\n");
        sb.append("# 服务器名（客户端可见）\n");
        sb.append("server.name=").append(serverName).append("\n");
        sb.append("# 版本号（握手包与 SELECT VERSION() 的返回值）\n");
        sb.append("server.version=").append(version).append("\n");
        sb.append("# 连接队列大小（TCP backlog）\n");
        sb.append("server.backlog=").append(backlog).append("\n");
        sb.append("# 最大并发连接数（线程池上限，最小 8）\n");
        sb.append("server.maxConn=").append(maxConn).append("\n\n");

        sb.append("# ===== 数据库（H2）=====\n");
        sb.append("# H2 2.x 数据文件路径（Java 11+）。相对路径基于本文件所在目录\n");
        sb.append("db.path=").append(rel(dbPath)).append("\n");
        sb.append("# H2 1.4.200 数据文件路径（Java 8）。格式与 2.x 不兼容，故独立存放\n");
        sb.append("db.path.legacy=").append(rel(dbPathLegacy)).append("\n");
        sb.append("# H2 内嵌库用户名（仅程序内部使用，不是客户端登录账号）\n");
        sb.append("db.user=").append(dbUser).append("\n");
        sb.append("# H2 内嵌库密码（同上，与客户端登录无关）\n");
        sb.append("db.password=").append(dbPassword).append("\n");
        sb.append("# 是否允许多进程共享同一个库文件（H2 AUTO_SERVER）。\n");
        sb.append("#   true  = 允许（默认）。但无 DNS/反向解析慢的环境下，首次打开库可能多等 20~30 秒\n");
        sb.append("#   false = 仅本进程独占库文件，启动更快（单开一个服务端时推荐）\n");
        sb.append("db.autoServer=").append(dbAutoServer).append("\n\n");

        sb.append("# ===== 日志 =====\n");
        sb.append("# 是否逐条记录 SQL（性能杀手，排障时再开）\n");
        sb.append("log.queries=").append(logQueries).append("\n");
        sb.append("# 是否输出到控制台\n");
        sb.append("log.console=").append(logConsole).append("\n");
        sb.append("# 日志文件路径（相对路径基于本配置文件所在目录）\n");
        sb.append("log.file=").append(rel(logFile)).append("\n");
        sb.append("# 最低输出级别：DEBUG / INFO / WARN / ERROR\n");
        sb.append("log.level=").append(logLevel).append("\n\n");

        sb.append("# ===== 认证 =====\n");
        sb.append("# true  = 严格校验客户端密码（默认，建议保持）\n");
        sb.append("# false = 宽松模式：未知用户直接放行（仅用于临时排障，切勿长期开启）\n");
        sb.append("auth.strict=").append(authStrict).append("\n\n");

        sb.append("# ===== 客户端登录账号 =====\n");
        sb.append("# 客户端连接 Mirage 用的账号密码（MySQL 协议层认证）。\n");
        sb.append("# 与上面的 db.user / db.password（H2 内部凭据）无关，不要混用。\n");
        sb.append("#\n");
        sb.append("# 出于安全考虑，Mirage 仅支持一个登录账号。\n");
        sb.append("#   密码留空 = 免密登录（仅建议本机调试时使用）\n");
        sb.append("#   用户名区分大小写；密码中的 = 号无需转义\n");
        sb.append("#\n");
        sb.append("# 默认账号：root / root —— 对外暴露前请务必修改密码。\n");
        sb.append("# 修改后重启生效。\n");
        String _u = users.isEmpty() ? "root" : users.keySet().iterator().next();
        String _p = users.isEmpty() ? "root" : users.values().iterator().next();
        sb.append("auth.username=").append(_u).append("\n");
        sb.append("auth.password=").append(_p).append("\n");
        // ---- 旧的多账号写法，已停用（保留以便回滚）----
        // for (Map.Entry<String, String> e : users.entrySet()) {
        //     sb.append("user.").append(e.getKey()).append("=").append(e.getValue()).append("\n");
        // }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (Exception e) {
            LogSink.error("[MirageConfig] 写入配置失败: " + e.getMessage());
        }
    }

    /** 写配置时尽量使用相对数据目录的路径，方便整体搬迁。 */
    private String rel(String p) {
        if (p == null) return "";
        if (baseDir == null) return p;
        try {
            String base = baseDir.getCanonicalPath().replace("\\", "/");
            String abs = new File(p).getCanonicalPath().replace("\\", "/");
            if (abs.startsWith(base + "/")) return "./" + abs.substring(base.length() + 1);
        } catch (Exception ignore) {}
        return p;
    }

    static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
