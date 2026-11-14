package mirage.core;

import java.sql.*;
import java.util.*;

public class SqlRouter {
    static final String BQ = String.valueOf((char)96);

    /** 在 H2 中注册 MySQL 缺失函数（内联 Java 别名）。 */
    static void registerFunctions(Connection h2) {
        // 全部通过 FOR "mirage.core.MySqlFunctions.xxx" 引用预编译方法。
        // 不使用 AS $$java源码$$（H2 会运行时动态编译，Java 25 下产出 class 69，
        // 导致 H2 自身 ClassReader 报 Unsupported class file major version 69）。
        // H2 2.x 不支持 CREATE OR REPLACE ALIAS，故先 DROP 再 CREATE。
        // 注意：DATE / TIME 是 H2 内置函数，无需（也无法）覆盖，故不在此注册。
        String[][] funcs = {
            {"REVERSE",         "reverse"},
            {"SUBSTRING_INDEX", "substringIndex"},
            {"FIELD",           "field"},
            {"DATE_FORMAT",     "dateFormat"}
        };
        for (String[] fn : funcs) {
            try (Statement st = h2.createStatement()) {
                try { st.execute("DROP ALIAS IF EXISTS " + fn[0]); } catch (Exception ignore) {}
                st.execute("CREATE ALIAS " + fn[0] + " FOR \"mirage.core.MySqlFunctions." + fn[1] + "\"");
            } catch (Exception e) {
                LogSink.warn("[SqlRouter] 注册函数 " + fn[0] + " 失败: " + e.getMessage());
            }
        }
    }

    static void init() throws Exception {
        Connection h2 = Db.conn();
        registerFunctions(h2);
        try (Statement st = h2.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(100), email VARCHAR(200), " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS products (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "title VARCHAR(200), price DECIMAL(10,2))");
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users");
            rs.next();
            if (rs.getInt(1) == 0) {
                st.execute("INSERT INTO users(name,email) VALUES ('alice','alice@example.com')");
                st.execute("INSERT INTO users(name,email) VALUES ('bob','bob@example.com')");
                st.execute("INSERT INTO products(title,price) VALUES ('Widget',19.99)");
                st.execute("INSERT INTO products(title,price) VALUES ('Gadget',29.99)");
            }
        }
    }

    static synchronized QueryResult route(String rawSql, Session session) {
        String sql = rawSql.trim();
        if (sql.startsWith("/*")) {
            int i = sql.indexOf("*/");
            if (i > 0) sql = sql.substring(i + 2).trim();
        }
        String low = sql.replace(BQ, "").replaceAll("\\s+", " ").toLowerCase();
        try {
            // 拒绝多语句（MySQL 默认禁止，防 SQL 注入）
            if (hasMultipleStatements(sql)) {
                return QueryResult.error(1064, "42000", "Mirage: multiple statements not allowed");
            }
            // 校验日期字面量范围（防"写进读不出"的数据损坏）
            if (low.startsWith("insert") || low.startsWith("update") || low.startsWith("replace")) {
                String dateErr = checkDateLiterals(sql);
                if (dateErr != null) {
                    return QueryResult.error(1292, "22007", dateErr);
                }
            }
            // ===== 连接仪式 =====
            if (low.startsWith("select @@")) return selectVars(sql);
            if (low.startsWith("select ") && isMultiFuncSelect(low)) return multiFuncSelect(sql, low, session);
            if (low.startsWith("select connection_id(")) return single("CONNECTION_ID()", String.valueOf(session.connId));
            if (low.startsWith("select database(")) return single("DATABASE()", session.currentDb);
            if (low.startsWith("select schema(")) return single("SCHEMA()", session.currentDb);
            if (low.startsWith("select now(") || low.startsWith("select current_timestamp")) return single("NOW()", now());
            if (low.startsWith("select curdate(") || low.startsWith("select current_date")) return single("CURDATE()", now().substring(0, 10));
            if (low.startsWith("select curtime(") || low.startsWith("select current_time")) return single("CURTIME()", now().substring(11));
            if (low.startsWith("select version(")) return single("VERSION()", versionStr());
            if (low.startsWith("select user(") || low.startsWith("select current_user")) return single("USER()", session.user + "@localhost");
            if (low.startsWith("select last_insert_id(")) return single("LAST_INSERT_ID()", String.valueOf(session.lastInsertId));

            // ===== 事务控制 =====
            if (low.startsWith("begin") || low.startsWith("start transaction")) {
                try { session.beginTx(); return ok(); } catch (SQLException e) { return QueryResult.error(1064, "42000", e.getMessage()); }
            }
            if (low.startsWith("commit")) {
                try { session.commitTx(); return ok(); } catch (SQLException e) { return QueryResult.error(1064, "42000", e.getMessage()); }
            }
            if (low.startsWith("rollback")) {
                try { session.rollbackTx(); return ok(); } catch (SQLException e) { return QueryResult.error(1064, "42000", e.getMessage()); }
            }
            // SET autocommit=0/1
            if (low.startsWith("set autocommit")) {
                boolean on = !low.contains("=0") && !low.contains(" = 0") && !low.contains("off");
                try { session.setAutoCommit(on); return ok(); } catch (SQLException e) { return QueryResult.error(1064, "42000", e.getMessage()); }
            }

            // ===== 会话控制 =====
            if (low.startsWith("set ") || low.startsWith("set names") || low.startsWith("set @@")) return ok();
            if (low.startsWith("use ")) {
                String db = sql.substring(4).trim().replace(BQ, "").replace(";", "").trim();
                try { session.switchDb(db); } catch (SQLException e) {
                    return QueryResult.error(1049, "42000", "Unknown database '" + db + "'");
                }
                return ok();
            }
            // ===== 用户管理 =====
            if (low.startsWith("create user")) return createUser(sql);
            if (low.startsWith("drop user")) return dropUser(sql);
            if (low.startsWith("grant") || low.startsWith("revoke")) return ok();
            if (low.startsWith("set password")) return ok();
            if (low.startsWith("flush privileges")) return ok();

            // ===== 数据库管理 =====
            if (low.startsWith("create database") || low.startsWith("create schema")) return createDatabase(sql);
            if (low.startsWith("drop database") || low.startsWith("drop schema")) return dropDatabase(sql);

            // ===== 表锁（导出时常用，直接忽略）=====
            if (low.startsWith("lock tables") || low.startsWith("unlock tables")) return ok();

            // ===== 存储过程/函数/触发器/事件（H2 不支持，优雅忽略）=====
            if (low.startsWith("create procedure") || low.startsWith("create function")
                || low.startsWith("create trigger") || low.startsWith("create event")) return ok();
            if (low.startsWith("drop procedure") || low.startsWith("drop function")
                || low.startsWith("drop trigger") || low.startsWith("drop event")) return ok();
            if (low.startsWith("alter procedure") || low.startsWith("alter function")
                || low.startsWith("alter event")) return ok();

            // ===== 表维护命令（MySQL 返回结果表，这里返回空表）=====
            if (low.startsWith("analyze table") || low.startsWith("optimize table")
                || low.startsWith("check table") || low.startsWith("repair table")
                || low.startsWith("checksum table")) return tableMaintenanceResult(sql);

            // ===== SHOW =====
            if (low.startsWith("show tables from information_schema") || low.startsWith("show tables in information_schema")) return MetaShow.informationSchemaTables();
            if (low.startsWith("show tables from mysql") || low.startsWith("show tables in mysql")) return MetaShow.emptyTables("mysql");
            if (low.startsWith("show databases")) return MetaShow.showDatabases();
            if (low.startsWith("show tables") || low.startsWith("show full tables")) return MetaShow.showTables(session, sql);
            if (low.startsWith("show create database") || low.startsWith("show create schema")) return MetaTable.showCreateDatabase(sql);
            if (low.startsWith("show create table")) return MetaTable.showCreateTable(MetaTable.extractTableName(sql), session);
            if (low.startsWith("show columns") || low.startsWith("show full columns") || low.startsWith("describe ") || low.startsWith("desc ")) return MetaTable.describeTable(MetaTable.extractTableName(sql), session);
            if (low.startsWith("show table status")) return MetaShow.showTableStatus(session, sql);
            if (low.startsWith("show variables")) return MetaShow.showVariables();
            if (low.startsWith("show ") && low.contains(" status")) return MetaShow.showStatus();
            if (low.startsWith("show engines")) return MetaShow.showEngines();
            if (low.startsWith("show charset") || low.startsWith("show character set")) return MetaShow.showCharset();
            if (low.startsWith("show collation")) return MetaShow.showCollation();
            if (low.startsWith("show processlist")) return MetaShow.emptyProcesslist(session);
            if (low.startsWith("show warnings") || low.startsWith("show errors")) return MetaShow.emptyWarnings();
            if (low.startsWith("show function status")) return MetaShow.emptyRoutine("FUNCTION");
            if (low.startsWith("show procedure status")) return MetaShow.emptyRoutine("PROCEDURE");
            if (low.startsWith("show triggers")) return MetaShow.emptyTriggers();
            if (low.startsWith("show events")) return MetaShow.emptyEvents();
            if (low.startsWith("show open tables")) return MetaShow.emptyOpenTables();
            if (low.startsWith("show grants")) return MetaShow.emptyGrants();
            if (low.startsWith("show create view") || low.startsWith("show create procedure")
                || low.startsWith("show create function") || low.startsWith("show create trigger")
                || low.startsWith("show create event")) return emptyShowCreate(sql);
            if (low.startsWith("show index") || low.startsWith("show keys")) return showIndex(MetaTable.extractTableName(sql), session);
            if (low.startsWith("show ")) return emptySingle("Value");

            // ===== information_schema =====
            if (low.startsWith("select") && (low.contains("mysql.innodb_index_stats") || low.contains("mysql.innodb_table_stats"))) return MetaShow.emptyInnodbStats();
            if (low.startsWith("select") && low.contains("information_schema")) {
                QueryResult ir = infoSchemaSelect(sql, low, session);
                if (ir != null) return ir;
            }

            // 方言翻译：ALTER VIEW → CREATE OR REPLACE VIEW
            if (low.startsWith("alter view")) {
                return forward(sql.replaceAll("(?i)^\\s*alter\\s+view", "CREATE OR REPLACE VIEW"), session);
            }

            // 方言翻译：RENAME TABLE a TO b → ALTER TABLE a RENAME TO b
            if (low.startsWith("rename table")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?i)rename\\s+table\\s+[" + BQ + "]?([a-z0-9_]+)[" + BQ + "]?\\s+to\\s+[" + BQ + "]?([a-z0-9_]+)[" + BQ + "]?").matcher(sql);
                if (m.find()) {
                    String translated = "ALTER TABLE " + m.group(1) + " RENAME TO " + m.group(2);
                    return forward(translated, session);
                }
            }

            return forward(sql, session);
        } catch (Exception e) {
            return QueryResult.error(1064, "42000", "Mirage: " + e.getMessage());
        }
    }

    /** 判断是否全是已知会话函数（database/user/version/connection_id/now/curdate/curtime/last_insert_id/@@var）。
     *  只有全是已知函数才拦截处理，否则交给 H2（避免误伤 YEAR()/ROUND() 等）。 */
    static boolean isMultiFuncSelect(String low) {
        if (low.contains(" from ")) return false;
        String body = low.substring(6).trim();
        String[] known = {"database(", "schema(", "user(", "current_user", "version(",
                          "connection_id(", "now(", "current_timestamp", "curdate(", "current_date",
                          "curtime(", "current_time", "last_insert_id(", "@@"};
        for (String part : splitTopLevel(body).toArray(new String[0])) {
            String p = part.trim();
            boolean isKnown = false;
            for (String k : known) { if (p.startsWith(k)) { isKnown = true; break; } }
            if (!isKnown) return false;
        }
        return true;
    }

    /** 处理 select database(), user(), version() 等组合。 */
    static QueryResult multiFuncSelect(String sql, String low, Session session) {
        String body = sql.substring(6).trim();
        body = body.replaceAll("\\s+as\\s+[\\w'\"]+", "");
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        List<String> row = new ArrayList<>();
        for (String part : splitTopLevel(body)) {
            String f = part.trim();
            String fl = f.toLowerCase();
            String colName = f;
            String val;
            if (fl.contains("connection_id(")) { val = String.valueOf(session.connId); }
            else if (fl.contains("database(") || fl.contains("schema(")) { val = session.currentDb; }
            else if (fl.contains("user(") || fl.contains("current_user")) { val = session.user + "@localhost"; }
            else if (fl.contains("version(")) { val = versionStr(); }
            else if (fl.contains("now(") || fl.contains("current_timestamp")) { val = now(); }
            else if (fl.contains("curdate(") || fl.contains("current_date")) { val = now().substring(0, 10); }
            else if (fl.contains("curtime(") || fl.contains("current_time")) { val = now().substring(11); }
            else if (fl.contains("last_insert_id(")) { val = String.valueOf(session.lastInsertId); }
            else if (fl.startsWith("@@")) { val = MetaShow.varValue(f.replaceAll("(?i)^@@(session\\.|global\\.|local\\.)?", "")); }
            else { val = ""; }
            qr.colNames.add(colName);
            qr.colTypes.add(253);
            row.add(val);
        }
        qr.rows.add(row);
        return qr;
    }

    /** 顶层逗号分割（不切分括号内）。 */
    static List<String> splitTopLevel(String s) {
        List<String> r = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) { r.add(s.substring(start, i)); start = i + 1; }
        }
        r.add(s.substring(start));
        return r;
    }

    // ===== 用户管理 =====
    // 出于安全考虑，Mirage 仅支持配置文件中的单一登录账号，
    // 因此 CREATE USER / DROP USER 不再动态增删账号，只返回 OK 让客户端继续。
    // 以下旧实现保留注释以便回滚。
    static QueryResult createUser(String sql) {
        // try {
        //     java.util.regex.Matcher m = java.util.regex.Pattern.compile(
        //         "(?i)create\\s+user\\s+(?:if\\s+not\\s+exists\\s+)?['\"]?([^'\"@\\s]+)['\"]?(?:@['\"]?([^'\"\\s]+)['\"]?)?\\s+identified\\s+by\\s+['\"]([^'\"]*)['\"]",
        //         java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        //     if (m.find()) {
        //         String user = m.group(1);
        //         String pwd = m.group(3);
        //         UserStore.addUser(user, pwd);
        //         return ok();
        //     }
        //     java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
        //         "(?i)create\\s+user\\s+(?:if\\s+not\\s+exists\\s+)?['\"]?([^'\"@\\s]+)['\"]?",
        //         java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        //     if (m2.find()) { UserStore.addUser(m2.group(1), ""); return ok(); }
        // } catch (Exception e) { /* ignore */ }
        return ok();
    }

    static QueryResult dropUser(String sql) {
        // java.util.regex.Matcher m = java.util.regex.Pattern.compile(
        //     "(?i)drop\\s+user\\s+(?:if\\s+exists\\s+)?['\"]?([^'\"@\\s]+)",
        //     java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        // if (m.find()) UserStore.removeUser(m.group(1));
        return ok();
    }

    // ===== 数据库管理 =====
    /** SHOW CREATE VIEW/PROCEDURE/FUNCTION/TRIGGER/EVENT —— 返回 2 列空结果。 */
    static QueryResult emptyShowCreate(String sql) {
        String obj = sql.replaceAll("(?i)^\\s*show\\s+create\\s+(view|procedure|function|trigger|event)\\s+", "")
                        .replace(BQ, "").replace(";", "").trim();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("View"); qr.colTypes.add(253);
        qr.colNames.add("Create View"); qr.colTypes.add(253);
        return qr;
    }

    /** SHOW INDEX / SHOW KEYS —— 从 H2 读取索引（简化：返回主键列）。 */
    static QueryResult showIndex(String tbl, Session session) throws SQLException {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Table","Non_unique","Key_name","Seq_in_index","Column_name","Collation","Cardinality","Sub_part","Packed","Null","Index_type","Comment","Index_comment","Visible","Expression"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        try (PreparedStatement ps = session.conn().prepareStatement(
                "SELECT COLUMN_NAME, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?) ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, session.h2Schema());
            ps.setString(2, tbl);
            try (ResultSet rs = ps.executeQuery()) {
                int seq = 1;
                while (rs.next()) {
                    boolean ident = false;
                    try { ident = rs.getBoolean(2); } catch (Exception ignore) {}
                    List<String> row = new ArrayList<>();
                    row.add(tbl.toLowerCase()); row.add(ident ? "0" : "1"); row.add(ident ? "PRIMARY" : "idx_" + rs.getString(1));
                    row.add(String.valueOf(seq++));
                    row.add(rs.getString(1).toLowerCase()); row.add("A"); row.add("0"); row.add(null); row.add(null);
                    row.add(""); row.add("BTREE"); row.add(""); row.add(""); row.add("YES"); row.add(null);
                    qr.rows.add(row);
                }
            }
        }
        return qr;
    }

    /** 表维护命令返回空结果表（ANALYZE/OPTIMIZE/CHECK/REPAIR）。 */
    static QueryResult tableMaintenanceResult(String sql) {
        String tbl = sql.replaceAll("(?i)^\\s*(analyze|optimize|check|repair|checksum)\\s+table\\s+", "")
                        .replace(BQ, "").replace(";", "").trim();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Table"); qr.colTypes.add(253);
        qr.colNames.add("Op"); qr.colTypes.add(253);
        qr.colNames.add("Msg_type"); qr.colTypes.add(253);
        qr.colNames.add("Msg_text"); qr.colTypes.add(253);
        List<String> row = new ArrayList<>();
        row.add(tbl); row.add("status"); row.add("status"); row.add("OK");
        qr.rows.add(row);
        return qr;
    }

    /** 库名含非标识符字符（如纯数字）时加双引号。 */
    static String quoteIdent(String name) {
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) return name;
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static QueryResult createDatabase(String sql) {
        String name = sql.replaceAll("(?i)^\\s*(create\\s+(database|schema))\\s+(if\\s+not\\s+exists\\s+)?", "")
                         .replace(BQ, "").replace(";", "").trim();
        // 去掉可能的 charset 子句
        int sp = name.indexOf(' ');
        if (sp > 0) name = name.substring(0, sp);
        if (name.isEmpty()) return QueryResult.error(1064, "42000", "database name required");
        String schema = name.equalsIgnoreCase("h2test") ? "PUBLIC" : name.toUpperCase();
        try {
            if (schema.equals("PUBLIC")) return ok();
            Db.withRetry(() -> {
                try (Statement st = conn().createStatement()) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdent(schema));
                }
                return null;
            });
            return ok();
        } catch (SQLException e) {
            return QueryResult.error(1007, "HY000", e.getMessage());
        }
    }

    static QueryResult dropDatabase(String sql) {
        String name = sql.replaceAll("(?i)^\\s*(drop\\s+(database|schema))\\s+(if\\s+exists\\s+)?", "")
                         .replace(BQ, "").replace(";", "").trim();
        if (name.isEmpty()) return QueryResult.error(1064, "42000", "database name required");
        String schema = name.equalsIgnoreCase("h2test") ? "PUBLIC" : name.toUpperCase();
        if (schema.equals("PUBLIC")) return QueryResult.error(1010, "HY000", "cannot drop h2test");
        try {
            Db.withRetry(() -> {
                try (Statement st = conn().createStatement()) {
                    st.execute("DROP SCHEMA IF EXISTS " + quoteIdent(schema) + " CASCADE");
                }
                return null;
            });
            return ok();
        } catch (SQLException e) {
            return QueryResult.error(1008, "HY000", e.getMessage());
        }
    }

    // ===== information_schema =====
    static QueryResult infoSchemaSelect(String sql, String low, Session session) throws SQLException {
        if (low.contains("information_schema.events")) return MetaShow.emptyEvents();
        if (low.contains("information_schema.triggers")) return MetaShow.emptyTriggers();
        if (low.contains("information_schema.routines")) return MetaShow.emptyRoutine("FUNCTION");
        if (low.contains("information_schema.schemata")) {
            if (low.contains("default_collation_name")) return single("DEFAULT_COLLATION_NAME", "utf8mb4_0900_ai_ci");
            return schemaList();
        }
        if (low.contains("information_schema.columns")) return columnsForTable(extractQuoted(sql, "TABLE_NAME"), extractQuoted(sql, "TABLE_SCHEMA"));
        if (low.contains("information_schema.referential_constraints")) return MetaShow.emptyRefConstraints();
        if (low.contains("information_schema.statistics")) return MetaShow.emptyStatistics();
        if (low.contains("information_schema.key_column_usage")) return MetaShow.emptyKeyColumnUsage();
        if (low.contains("information_schema.tables")) return infoSchemaTablesQ(extractQuoted(sql, "TABLE_SCHEMA"));
        if (low.contains("information_schema.views")) {
            QueryResult qr = new QueryResult(); qr.isResultset = true;
            qr.colNames.add("TABLE_NAME"); qr.colTypes.add(253);
            return qr;
        }
        if (low.contains("information_schema.character_sets")) return infoSchemaEmpty("CHARACTER_SETS");
        if (low.contains("information_schema.collations")) return infoSchemaEmpty("COLLATIONS");
        if (low.contains("information_schema.collation_character_set_applicability")) return infoSchemaEmpty("COLLATION_CHARACTER_SET_APPLICABILITY");
        if (low.contains("information_schema.engines")) return infoSchemaEmpty("ENGINES");
        if (low.contains("information_schema.plugins")) return infoSchemaEmpty("PLUGINS");
        if (low.contains("information_schema.processlist")) return MetaShow.emptyProcesslist(session);
        if (low.contains("information_schema.partitions")) return infoSchemaEmpty("PARTITIONS");
        if (low.contains("information_schema.files")) return infoSchemaEmpty("FILES");
        if (low.contains("information_schema.parameters")) return infoSchemaEmpty("PARAMETERS");
        if (low.contains("information_schema.tablespaces")) return infoSchemaEmpty("TABLESPACES");
        if (low.contains("information_schema.column_privileges")) return infoSchemaEmpty("COLUMN_PRIVILEGES");
        if (low.contains("information_schema.user_privileges")) return infoSchemaEmpty("USER_PRIVILEGES");
        if (low.contains("information_schema.schema_privileges")) return infoSchemaEmpty("SCHEMA_PRIVILEGES");
        if (low.contains("information_schema.table_privileges")) return infoSchemaEmpty("TABLE_PRIVILEGES");
        if (low.contains("information_schema.table_constraints")) return infoSchemaEmpty("TABLE_CONSTRAINTS");
        if (low.contains("information_schema.check_constraints")) return infoSchemaEmpty("CHECK_CONSTRAINTS");
        if (low.contains("information_schema.st_spatial_reference_systems")) return infoSchemaEmpty("ST_SPATIAL_REFERENCE_SYSTEMS");
        return null;
    }

    static final java.util.Map<String, String[]> INFO_SCHEMA_COLS = new java.util.HashMap<>();
    static {
        INFO_SCHEMA_COLS.put("CHARACTER_SETS", new String[]{"CHARACTER_SET_NAME","DEFAULT_COLLATE_NAME","DESCRIPTION","MAXLEN"});
        INFO_SCHEMA_COLS.put("COLLATIONS", new String[]{"COLLATION_NAME","CHARACTER_SET_NAME","ID","IS_DEFAULT","IS_COMPILED","SORTLEN","PAD_ATTRIBUTE"});
        INFO_SCHEMA_COLS.put("COLLATION_CHARACTER_SET_APPLICABILITY", new String[]{"COLLATION_NAME","CHARACTER_SET_NAME"});
        INFO_SCHEMA_COLS.put("ENGINES", new String[]{"ENGINE","SUPPORT","COMMENT","TRANSACTIONS","XA","SAVEPOINTS"});
        INFO_SCHEMA_COLS.put("PLUGINS", new String[]{"PLUGIN_NAME","PLUGIN_VERSION","PLUGIN_STATUS","PLUGIN_TYPE","PLUGIN_LIBRARY","PLUGIN_LICENSE","LOAD_OPTION"});
        INFO_SCHEMA_COLS.put("PARTITIONS", new String[]{"TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","PARTITION_NAME","SUBPARTITION_NAME","PARTITION_ORDINAL_POSITION","PARTITION_METHOD","PARTITION_EXPRESSION","PARTITION_DESCRIPTION","TABLE_ROWS"});
        INFO_SCHEMA_COLS.put("FILES", new String[]{"FILE_ID","FILE_NAME","FILE_TYPE","TABLESPACE_NAME","ENGINE","STATUS","EXTRA"});
        INFO_SCHEMA_COLS.put("PARAMETERS", new String[]{"SPECIFIC_CATALOG","SPECIFIC_SCHEMA","SPECIFIC_NAME","ORDINAL_POSITION","PARAMETER_MODE","PARAMETER_NAME","DATA_TYPE"});
        INFO_SCHEMA_COLS.put("TABLESPACES", new String[]{"TABLESPACE_NAME","ENGINE","TABLESPACE_TYPE","LOGFILE_GROUP_NAME","EXTENT_SIZE","AUTOEXTEND_SIZE","STATUS"});
        INFO_SCHEMA_COLS.put("COLUMN_PRIVILEGES", new String[]{"GRANTEE","TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","PRIVILEGE_TYPE","IS_GRANTABLE"});
        INFO_SCHEMA_COLS.put("USER_PRIVILEGES", new String[]{"GRANTEE","TABLE_CATALOG","PRIVILEGE_TYPE","IS_GRANTABLE"});
        INFO_SCHEMA_COLS.put("SCHEMA_PRIVILEGES", new String[]{"GRANTEE","TABLE_CATALOG","TABLE_SCHEMA","PRIVILEGE_TYPE","IS_GRANTABLE"});
        INFO_SCHEMA_COLS.put("TABLE_PRIVILEGES", new String[]{"GRANTEE","TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","PRIVILEGE_TYPE","IS_GRANTABLE"});
        INFO_SCHEMA_COLS.put("TABLE_CONSTRAINTS", new String[]{"CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","TABLE_SCHEMA","TABLE_NAME","CONSTRAINT_TYPE","ENFORCED"});
        INFO_SCHEMA_COLS.put("CHECK_CONSTRAINTS", new String[]{"CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","CHECK_CLAUSE"});
        INFO_SCHEMA_COLS.put("ST_SPATIAL_REFERENCE_SYSTEMS", new String[]{"SRS_NAME","SRS_ID","ORGANIZATION","ORGANIZATION_COORDSYS_ID","DEFINITION","DESCRIPTION"});
    }

    static QueryResult infoSchemaEmpty(String table) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = INFO_SCHEMA_COLS.get(table);
        if (cols == null) cols = new String[]{"Value"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static String extractQuoted(String sql, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(key + "\\s*=\\s*'([^']*)'", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        if (m.find()) return m.group(1);
        return "";
    }

    static String toH2Schema(String logicalDb) {
        if (logicalDb == null || logicalDb.isEmpty()) return "PUBLIC";
        String d = logicalDb.toLowerCase();
        if (d.equals("h2test") || d.equals("public")) return "PUBLIC";
        return logicalDb.toUpperCase();
    }

    static QueryResult columnsForTable(String tbl, String dbArg) throws SQLException {
        String schema = toH2Schema(dbArg);
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","ORDINAL_POSITION","COLUMN_DEFAULT","IS_NULLABLE","DATA_TYPE","CHARACTER_MAXIMUM_LENGTH","CHARACTER_OCTET_LENGTH","NUMERIC_PRECISION","NUMERIC_SCALE","DATETIME_PRECISION","CHARACTER_SET_NAME","COLLATION_NAME","COLUMN_TYPE","COLUMN_KEY","EXTRA","PRIVILEGES","COLUMN_COMMENT"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        qr.schema = dbArg;
        String shownDb = dbArg.isEmpty() ? "h2test" : dbArg;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?) ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, tbl);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1).toLowerCase();
                    String type = rs.getString(5);
                    Object cl = rs.getObject(6);
                    String isNull = "YES".equals(rs.getString(4)) ? "YES" : "NO";
                    Object def = rs.getObject(3);
                    boolean identity = false;
                    try { identity = rs.getBoolean(7); } catch (Exception ignore) {}
                    List<String> row = new ArrayList<>();
                    row.add("def"); row.add(shownDb); row.add(tbl.toLowerCase()); row.add(name);
                    row.add(String.valueOf(rs.getInt(2)));
                    row.add(def == null ? null : String.valueOf(def));
                    row.add(isNull);
                    row.add(MetaTable.toMysqlBaseType(type));
                    row.add(cl == null ? null : String.valueOf(cl));
                    row.add(cl == null ? null : String.valueOf(cl));
                    row.add(null); row.add(null); row.add(null);
                    row.add("utf8mb4"); row.add("utf8mb4_0900_ai_ci");
                    row.add(MetaTable.toMysqlType(type, cl));
                    row.add(identity ? "PRI" : "");
                    row.add(identity ? "auto_increment" : "");
                    row.add("select,insert,update,references");
                    row.add("");
                    qr.rows.add(row);
                }
            }
        }
        return qr;
    }

    static QueryResult infoSchemaTablesQ(String dbArg) throws SQLException {
        String schema = toH2Schema(dbArg);
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        String[] cols = {"TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","TABLE_TYPE","ENGINE","VERSION","ROW_FORMAT","TABLE_ROWS","AVG_ROW_LENGTH","DATA_LENGTH","MAX_DATA_LENGTH","INDEX_LENGTH","DATA_FREE","AUTO_INCREMENT","CREATE_TIME","UPDATE_TIME","CHECK_TIME","TABLE_COLLATION","CHECKSUM","CREATE_OPTIONS","TABLE_COMMENT"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        String shownDb = dbArg.isEmpty() ? "h2test" : dbArg;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1).toLowerCase();
                    List<String> row = new ArrayList<>();
                    row.add("def"); row.add(shownDb); row.add(t); row.add("BASE TABLE");
                    row.add("InnoDB"); row.add("10"); row.add("Dynamic"); row.add("0");
                    row.add("0"); row.add("16384"); row.add("0"); row.add("0"); row.add("0");
                    row.add("1"); row.add("2026-01-01 00:00:00"); row.add(null); row.add(null);
                    row.add("utf8mb4_0900_ai_ci"); row.add(null); row.add(""); row.add("");
                    qr.rows.add(row);
                }
            }
        }
        return qr;
    }

    static QueryResult schemaList() throws SQLException {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        qr.colNames.add("SCHEMA_NAME"); qr.colTypes.add(253);
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA') ORDER BY SCHEMA_NAME")) {
            while (rs.next()) {
                String s = rs.getString(1);
                if (s.equalsIgnoreCase("PUBLIC")) qr.rows.add(new ArrayList<>(Collections.singletonList("h2test")));
                else qr.rows.add(new ArrayList<>(Collections.singletonList(s.toLowerCase())));
            }
        }
        qr.rows.add(new ArrayList<>(Collections.singletonList("information_schema")));
        return qr;
    }

    /** 剥离 CREATE TABLE 后的 MySQL 专有表选项（COLLATE/ENGINE/CHARSET/COMMENT 等），H2 不支持。 */
    static String cleanMysqlTableOptions(String sql) {
        if (!sql.toLowerCase().startsWith("create table")) return sql;
        int lastParen = sql.lastIndexOf(')');
        if (lastParen < 0) return sql;
        String head = sql.substring(0, lastParen + 1);
        String tail = sql.substring(lastParen + 1);
        tail = tail.replaceAll("(?i)\\s*COLLATE\\s*=?\\s*'[^']*'", "");
        tail = tail.replaceAll("(?i)\\s*COLLATE\\s*=?\\s*[a-zA-Z0-9_]+", "");
        tail = tail.replaceAll("(?i)\\s*ENGINE\\s*=\\s*[a-zA-Z0-9_]+", "");
        tail = tail.replaceAll("(?i)\\s*DEFAULT\\s+CHARSET\\s*=\\s*[a-zA-Z0-9_]+", "");
        tail = tail.replaceAll("(?i)\\s*CHARSET\\s*=\\s*[a-zA-Z0-9_]+", "");
        tail = tail.replaceAll("(?i)\\s*AUTO_INCREMENT\\s*=\\s*\\d+", "");
        tail = tail.replaceAll("(?i)\\s*ROW_FORMAT\\s*=\\s*[a-zA-Z0-9_]+", "");
        tail = tail.replaceAll("(?i)\\s*COMMENT\\s*=\\s*'[^']*'", "");
        tail = tail.replaceAll("(?i)\\s*COLLATE\\s*=\\s*'[^']*'", "");
        tail = tail.trim();
        if (tail.isEmpty() || tail.equals(";")) return head + ";";
        return head + " " + tail;
    }

    /** 引号感知地去除 schema 前缀：跳过单/双引号内的字符串，只处理标识符区域。
     *  处理形式：`schema`. / schema. （反引号是标识符引用，需处理；单双引号是字符串，跳过）。 */
    static String stripSchemaPrefixes(String sql, java.util.Set<String> schemas) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 字符串字面量：原样复制，不处理
            if (c == '\'' || c == '"') {
                char quote = c;
                out.append(c); i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) { out.append(sql.charAt(i + 1)); i += 2; continue; }
                    if (d == quote) { i++; break; }
                    i++;
                }
                continue;
            }
            // 反引号标识符前缀：`schema`.
            if (c == BQ.charAt(0)) {
                int j = i + 1, k = j;
                while (k < n && sql.charAt(k) != BQ.charAt(0)) k++;
                if (k < n) {
                    String inner = sql.substring(j, k);
                    if (schemas.contains(inner.toLowerCase()) && k + 1 < n && sql.charAt(k + 1) == '.') {
                        i = k + 2;   // 跳过 `schema`.
                        continue;
                    }
                    out.append(sql, i, k + 1); i = k + 1;
                    continue;
                }
                out.append(c); i++; continue;
            }
            // 裸标识符 schema. （要求前面是边界、后面是标识符或反引号）
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
                String word = sql.substring(i, j);
                boolean prevOk = (out.length() == 0) || !(Character.isLetterOrDigit(out.charAt(out.length()-1)) || out.charAt(out.length()-1) == '_');
                if (prevOk && schemas.contains(word.toLowerCase()) && j < n && sql.charAt(j) == '.') {
                    i = j + 1;   // 跳过 schema.
                    continue;
                }
                out.append(word); i = j; continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    /** MySQL → H2 方言翻译（best-effort）。
     *  - SET('a','b') 类型 → VARCHAR
     *  - INT(11)/FLOAT(10,2) 显示宽度 → 去掉宽度
     *  - IF(c,a,b) → CASEWHEN(c,a,b)
     *  - DATEDIFF(a,b) → DATEDIFF('DAY',a,b) */
    static String translateDialect(String s) {
        // SET 类型
        s = s.replaceAll("(?i)\\bSET\\s*\\([^)]*\\)", "VARCHAR(255)");
        // 整数/浮点显示宽度
        s = s.replaceAll("(?i)\\b(INT|INTEGER|TINYINT|SMALLINT|MEDIUMINT|BIGINT|FLOAT|DOUBLE)\\s*\\(\\s*\\d+\\s*(,\\s*\\d+\\s*)?\\)", "$1");
        // IF(c,a,b) → CASEWHEN(c,a,b)
        s = s.replaceAll("(?i)\\bIF\\s*\\(", "CASEWHEN(");
        // DATEDIFF(a,b)：H2 返回 b-a，MySQL 返回 a-b → 取负
        s = s.replaceAll("(?i)\\bDATEDIFF\\s*\\(", "-DATEDIFF('DAY',");
        // DATE_ADD(d, INTERVAL n UNIT) → DATEADD('UNIT', n, d)
        s = java.util.regex.Pattern.compile(
            "(?i)DATE_ADD\\s*\\(\\s*([^,]+?)\\s*,\\s*INTERVAL\\s+(-?\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND|WEEK)\\s*\\)"
        ).matcher(s).replaceAll("DATEADD('$3', $2, $1)");
        // DATE_SUB(d, INTERVAL n UNIT) → DATEADD('UNIT', -n, d)
        s = java.util.regex.Pattern.compile(
            "(?i)DATE_SUB\\s*\\(\\s*([^,]+?)\\s*,\\s*INTERVAL\\s+(-?\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND|WEEK)\\s*\\)"
        ).matcher(s).replaceAll("DATEADD('$3', -($2), $1)");
        // STR_TO_DATE(s, '%Y-%m-%d') → PARSEDATETIME(s, 'yyyy-MM-dd')
        s = java.util.regex.Pattern.compile(
            "(?i)STR_TO_DATE\\s*\\(\\s*('[^']*'|[^,]+?)\\s*,\\s*'([^']*)'\\s*\\)"
        ).matcher(s).replaceAll("PARSEDATETIME($1, '$2')");
        s = convertDateFormat(s);
        // UNSIGNED 修饰符（H2 不支持）
        s = s.replaceAll("(?i)\\bUNSIGNED\\b", "");
        // REVERSE 不支持，保留（H2 无），跳过
        return s;
    }

    /** 把 PARSEDATETIME 里的 MySQL 日期格式串转成 Java 格式（%Y→yyyy 等）。
     *  Java 8 兼容：不用 Matcher.replaceAll(Function)（Java 9+）。 */
    static String convertDateFormat(String s) {
        if (!s.toLowerCase().contains("parsedatetime")) return s;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']*)'").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fmt = m.group(1);
            String repl;
            if (!fmt.contains("%")) {
                repl = "'" + fmt + "'";
            } else {
                String j = fmt.replace("%Y","yyyy").replace("%y","yy")
                              .replace("%m","MM").replace("%c","M")
                              .replace("%d","dd").replace("%e","d")
                              .replace("%H","HH").replace("%i","mm").replace("%s","ss")
                              .replace("%M","MMMM").replace("%b","MMM")
                              .replace("%W","EEEE").replace("%a","EEE")
                              .replace("%T","HH:mm:ss").replace("%p","a")
                              .replace("%%","%");
                repl = "'" + j + "'";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 检测是否含多条语句（顶层分号后有内容）。引号感知，避免误伤字符串内的分号。 */
    static boolean hasMultipleStatements(String sql) {
        int depth = 0;              // 括号深度
        char inQuote = 0;           // 当前引号类型
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inQuote != 0) {
                if (c == '\\') { i++; continue; }   // 转义
                if (c == inQuote) inQuote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == BQ.charAt(0)) { inQuote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) {
                // 分号后跳过所有空白和注释，看是否还有内容
                int j = i + 1;
                while (j < sql.length()) {
                    char d = sql.charAt(j);
                    if (Character.isWhitespace(d)) { j++; continue; }
                    if (d == '-' && j + 1 < sql.length() && sql.charAt(j+1) == '-') {
                        // 行注释：跳到行尾
                        while (j < sql.length() && sql.charAt(j) != '\n') j++;
                        continue;
                    }
                    if (d == '/' && j + 1 < sql.length() && sql.charAt(j+1) == '*') {
                        // 块注释：跳到 */
                        int end = sql.indexOf("*/", j + 2);
                        j = (end < 0) ? sql.length() : end + 2;
                        continue;
                    }
                    // 非空白非注释 → 有多条语句
                    return true;
                }
            }
        }
        return false;
    }

    /** 校验日期字面量年份范围（MySQL DATE/DATETIME 为 1000-9999）。
     *  返回越界描述，或 null 表示合法。 */
    static String checkDateLiterals(String sql) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "'([+-]?\\d{4,})-(\\d{1,2})-(\\d{1,2})").matcher(sql);
        while (m.find()) {
            int year = Integer.parseInt(m.group(1).replace("+", ""));
            if (year < 1000 || year > 9999) {
                return "Incorrect date value: year " + year + " out of range (1000-9999)";
            }
        }
        return null;
    }

    /** 获取当前应使用的 H2 连接：优先会话独立连接（线程绑定），否则回退共享连接。 */
    static Connection conn() throws SQLException {
        Session s = Session.current();
        return s != null ? s.conn() : Db.conn();
    }

    // ===== 转发到 H2 =====
    static QueryResult forward(String sql, Session session) throws SQLException {
        String s = sql;
        // 引号感知地去除 schema 前缀（跳过字符串字面量，避免误伤数据）
        java.util.Set<String> schemas = new java.util.HashSet<>();
        schemas.add("h2test"); schemas.add("public");
        if (session.currentDb != null) schemas.add(session.currentDb.toLowerCase());
        s = stripSchemaPrefixes(s, schemas);
        // 剥离 MySQL 专有表选项（H2 不支持）
        s = cleanMysqlTableOptions(s);
        // 方言翻译（H2 不支持的语法 → 等价形式）
        s = translateDialect(s);

        final String finalSql = s;
        // 判断是否为插入语句（用于捕获自增 ID）
        boolean isInsert = finalSql.trim().toLowerCase().startsWith("insert");
        return Db.withRetry(() -> {
            Connection cc = session.conn();
            try (Statement st = cc.createStatement()) {
                boolean hasRs = st.execute(finalSql);
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) { return fromResultSet(rs, session); }
                }
                QueryResult qr = new QueryResult();
                qr.affectedRows = st.getUpdateCount();
                if (isInsert && qr.affectedRows > 0) {
                    try {
                        long id = identityOf(cc, finalSql);
                        if (id > 0) { qr.lastInsertId = id; session.lastInsertId = id; }
                    } catch (Exception ignore) {}
                }
                return qr;
            } catch (SQLException ex) {
                // 执行失败：若涉及已知坏表，给出明确告警
                String tbl = extractTargetTable(finalSql);
                if (DbRecovery.isBroken(tbl)) {
                    throw new SQLException("[损坏表警告] 表 '" + tbl + "' 数据已损坏。"
                        + "此表仍可通过 DROP TABLE / ALTER TABLE 修复或删除；"
                        + "如需恢复数据请从备份。原始错误: " + ex.getMessage(), ex);
                }
                throw ex;
            }
        });
    }

    /** 从 SQL 中粗略提取目标表名（用于坏表告警）。 */
    static String extractTargetTable(String sql) {
        String low = sql.toLowerCase().trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?i)\\b(?:from|into|update|table|join)\\s+([^\\s(;,]+)").matcher(low);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) return null;
        last = last.replace("`", "").replace("\"", "");
        int dot = last.lastIndexOf('.');
        return dot >= 0 ? last.substring(dot + 1) : last;
    }

    /** 获取本连接最后插入的自增 ID（H2 的 LAST_INSERT_ID()）。 */
    static long identityOf(Connection cc, String sql) {
        try (Statement st = cc.createStatement(); ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception ignore) {}
        return 0;
    }

    static QueryResult fromResultSet(ResultSet rs, Session session) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.schema = session.currentDb;
        for (int i = 1; i <= n; i++) {
            qr.colNames.add(md.getColumnLabel(i).toLowerCase());
            qr.colTypes.add(mapType(md.getColumnType(i)));
        }
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                String v = rs.getString(i);
                if (v != null && v.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")) {
                    v = v.replaceAll("\\.\\d+$", "");
                }
                row.add(v);
            }
            qr.rows.add(row);
        }
        return qr;
    }

    static int mapType(int jdbcType) {
        switch (jdbcType) {
            case Types.INTEGER: return 3;
            case Types.BIGINT: return 8;
            case Types.SMALLINT: return 2;
            case Types.TINYINT: return 1;
            case Types.DECIMAL:
            case Types.NUMERIC: return 246;
            case Types.FLOAT:
            case Types.REAL: return 4;
            case Types.DOUBLE: return 5;
            case Types.DATE: return 10;
            case Types.TIME: return 11;
            case Types.TIMESTAMP: return 12;
            case Types.BOOLEAN:
            case Types.BIT: return 1;
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY: return 252;
            default: return 253;
        }
    }

    // ===== 工具 =====
    static QueryResult single(String col, String val) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add(col); qr.colTypes.add(253);
        qr.rows.add(new ArrayList<>(Collections.singletonList(val)));
        return qr;
    }
    static QueryResult emptySingle(String col) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add(col); qr.colTypes.add(253);
        return qr;
    }
    static QueryResult ok() { return new QueryResult(); }
    static String now() { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()); }
    /** 服务器版本号（来自配置）。 */
    static String versionStr() {
        return (FakeMysql.CONFIG != null) ? FakeMysql.CONFIG.version : "8.4.0-mirage";
    }

    // ===== SELECT @@vars =====
    static QueryResult selectVars(String sql) {
        String body = sql.substring(sql.toLowerCase().indexOf("@@")).trim();
        int li = body.toLowerCase().indexOf(" limit ");
        if (li > 0) body = body.substring(0, li);
        List<String> names = new ArrayList<>();
        for (String part : body.split(",")) {
            String v = part.trim();
            int asIdx = v.toLowerCase().indexOf(" as ");
            if (asIdx > 0) {
                names.add(v.substring(asIdx + 4).trim().replaceAll("[" + BQ + "'\"\\s]", ""));
            } else {
                String n = v.replaceAll("(?i)^@@(session\\.|global\\.|local\\.)?", "").trim();
                n = n.replaceAll("[;\\s].*$", "");
                names.add(n);
            }
        }
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        for (String n : names) { qr.colNames.add(n); qr.colTypes.add(253); }
        List<String> row = new ArrayList<>();        for (String n : names) row.add(MetaShow.varValue(n));
        qr.rows.add(row);
        return qr;
    }
}
