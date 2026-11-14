package mirage.core;

import java.sql.*;
import java.util.*;

/** Mirage - SHOW 命令、会话变量、空结果辅助（从 SqlRouter 拆出）。 */
public class MetaShow {
    static final String BQ = SqlRouter.BQ;

    static String varValue(String name) {
        String n = name.toLowerCase();
        if (n.contains("auto_increment_increment")) return "1";
        if (n.contains("auto_increment_offset")) return "1";
        if (n.contains("autocommit")) {
            Session cs = Session.current();
            return (cs != null && !cs.autocommit) ? "OFF" : "ON";
        }
        if (n.contains("character_set_client")) return "utf8mb4";
        if (n.contains("character_set_connection")) return "utf8mb4";
        if (n.contains("character_set_database")) return "utf8mb4";
        if (n.contains("character_set_results")) return "utf8mb4";
        if (n.contains("character_set_server")) return "utf8mb4";
        if (n.contains("character_set_system")) return "utf8mb3";
        if (n.contains("collation_connection")) return "utf8mb4_0900_ai_ci";
        if (n.contains("collation_database")) return "utf8mb4_0900_ai_ci";
        if (n.contains("collation_server")) return "utf8mb4_0900_ai_ci";
        if (n.contains("init_connect")) return "";
        if (n.contains("interactive_timeout")) return "28800";
        if (n.contains("license")) return "GPL";
        if (n.contains("lower_case_table_names")) return "1";
        if (n.contains("max_allowed_packet")) return "67108864";
        if (n.contains("net_write_timeout")) return "60";
        if (n.contains("performance_schema")) return "OFF";
        if (n.contains("sql_mode")) return "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";
        if (n.contains("system_time_zone")) return "CST";
        if (n.contains("time_zone")) return "SYSTEM";
        if (n.contains("transaction_isolation") || n.contains("tx_isolation")) return "REPEATABLE-READ";
        if (n.contains("transaction_read_only") || n.contains("tx_read_only")) return "0";
        if (n.contains("auto_increment")) return "1";
        if (n.contains("sql_select_limit")) return "18446744073709551615";
        if (n.contains("default_week_format")) return "0";
        if (n.contains("group_concat_max_len")) return "1024";
        if (n.contains("max_execution_time")) return "0";
        if (n.contains("max_sort_length")) return "1024";
        if (n.contains("max_join_size")) return "18446744073709551615";
        if (n.contains("min_examined_row_limit")) return "0";
        if (n.contains("sql_big_selects")) return "1";
        if (n.contains("sql_buffer_result")) return "OFF";
        if (n.contains("sql_log_off")) return "OFF";
        if (n.contains("sql_notes")) return "ON";
        if (n.contains("sql_quote_show_create")) return "ON";
        if (n.contains("sql_require_primary_key")) return "OFF";
        if (n.contains("sql_warnings")) return "OFF";
        if (n.contains("unique_checks")) return "ON";
        if (n.contains("foreign_key_checks")) return "ON";
        if (n.contains("query_cache")) return "0";
        if (n.contains("optimizer_")) return "1";
        if (n.contains("read_only")) return "OFF";
        if (n.contains("super_read_only")) return "OFF";
        if (n.contains("wait_timeout")) return "28800";
        if (n.contains("version_comment")) return "Mirage (H2 bridge)";
        if (n.contains("version_compile_machine")) return "x86_64";
        if (n.contains("version_compile_os")) return "Win64";
        if (n.contains("innodb_version")) return "8.4.0";
        if (n.contains("version")) return SqlRouter.versionStr();
        if (n.contains("hostname")) return "mirage";
        if (n.contains("socket")) return "MySQL";
        if (n.contains("datadir")) return "./data/";
        if (n.contains("basedir")) return ".";
        if (n.contains("tmpdir")) return System.getProperty("java.io.tmpdir");
        if (n.contains("innodb_buffer_pool_size")) return "134217728";
        if (n.contains("innodb_flush_log_at_trx_commit")) return "1";
        if (n.contains("innodb_")) return "1";
        if (n.contains("port")) return String.valueOf(FakeMysql.CONFIG != null ? FakeMysql.CONFIG.port : 3306);
        if (n.contains("protocol_version")) return "10";
        if (n.contains("default_storage_engine")) return "InnoDB";
        if (n.contains("have_ssl")) return "DISABLED";
        if (n.contains("have_query_cache")) return "NO";
        if (n.contains("query_cache_size")) return "0";
        if (n.contains("query_cache_type")) return "OFF";
        if (n.contains("log_bin")) return "OFF";
        if (n.contains("max_connections")) return "151";
        if (n.contains("sql_auto_is_null")) return "OFF";
        if (n.contains("sql_safe_updates")) return "OFF";
        if (n.contains("default_authentication_plugin")) return "mysql_native_password";
        if (n.contains("max_connect_errors")) return "100";
        if (n.contains("net_buffer_length")) return "16384";
        if (n.contains("net_read_timeout")) return "30";
        return "";
    }

    static QueryResult showVariables() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Variable_name"); qr.colTypes.add(253);
        qr.colNames.add("Value"); qr.colTypes.add(253);
        String[] keys = {"auto_increment_increment","auto_increment_offset","autocommit","character_set_client",
            "character_set_connection","character_set_database","character_set_results","character_set_server",
            "character_set_system","collation_connection","collation_database","collation_server","init_connect",
            "interactive_timeout","license","lower_case_table_names","max_allowed_packet","net_buffer_length",
            "net_read_timeout","net_write_timeout","performance_schema","sql_mode","system_time_zone","time_zone",
            "transaction_isolation","tx_isolation","version","version_comment","version_compile_machine",
            "version_compile_os","wait_timeout","hostname","port","protocol_version","default_storage_engine",
            "have_ssl","have_query_cache","query_cache_size","query_cache_type","log_bin","max_connections",
            "sql_auto_is_null","sql_safe_updates","default_authentication_plugin","max_connect_errors"};
        for (String k : keys) {
            List<String> row = new ArrayList<>();
            row.add(k); row.add(varValue(k));
            qr.rows.add(row);
        }
        return qr;
    }

    static QueryResult showStatus() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Variable_name"); qr.colTypes.add(253);
        qr.colNames.add("Value"); qr.colTypes.add(253);
        String[][] data = {
            {"Uptime","100"},{"Threads_connected","1"},{"Threads_running","1"},{"Questions","42"},
            {"Connections","1"},{"Bytes_received","1024"},{"Bytes_sent","2048"},{"Aborted_clients","0"},
            {"Aborted_connects","0"},{"Slow_queries","0"},{"Opened_tables","5"},{"Open_tables","5"}
        };
        for (String[] kv : data) qr.rows.add(new ArrayList<>(Arrays.asList(kv)));
        return qr;
    }

    static QueryResult showDatabases() throws SQLException { return SqlRouter.schemaList(); }

    static QueryResult showTables(Session session, String sql) throws SQLException {
        String db = session.currentDb;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(from|in)\\s+[" + BQ + "]?([a-z0-9_]+)[" + BQ + "]?").matcher(sql);
        if (m.find()) db = m.group(2);
        String schema = SqlRouter.toH2Schema(db);
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Tables_in_" + db); qr.colTypes.add(253);
        try (PreparedStatement ps = session.conn().prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) qr.rows.add(new ArrayList<>(Collections.singletonList(rs.getString(1).toLowerCase())));
            }
        }
        return qr;
    }

    static QueryResult emptyTables(String db) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Tables_in_" + db); qr.colTypes.add(253);
        return qr;
    }

    static QueryResult informationSchemaTables() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Tables_in_information_schema"); qr.colTypes.add(253);
        String[] t = {"CHARACTER_SETS","COLLATIONS","COLUMN_PRIVILEGES","COLUMNS","ENGINES","EVENTS","FILES","KEY_COLUMN_USAGE","PARAMETERS","PARTITIONS","PLUGINS","PROCESSLIST","REFERENTIAL_CONSTRAINTS","ROUTINES","SCHEMATA","STATISTICS","TABLES","TABLESPACES","TRIGGERS","VIEWS"};
        for (String x : t) qr.rows.add(new ArrayList<>(Collections.singletonList(x.toLowerCase())));
        return qr;
    }

    static QueryResult showTableStatus(Session session, String sql) throws SQLException {
        String db = session.currentDb;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(from|in)\\s+[" + BQ + "]?([a-z0-9_]+)[" + BQ + "]?").matcher(sql);
        if (m.find()) db = m.group(2);
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Name","Engine","Version","Row_format","Rows","Avg_row_length","Data_length","Max_data_length","Index_length","Data_free","Auto_increment","Create_time","Update_time","Check_time","Collation","Checksum","Create_options","Comment"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        if (db.equalsIgnoreCase("information_schema")) {
            String[] infoTables = {"CHARACTER_SETS","COLLATIONS","COLUMN_PRIVILEGES","COLUMNS","ENGINES","EVENTS","FILES","KEY_COLUMN_USAGE","PARAMETERS","PARTITIONS","PLUGINS","PROCESSLIST","REFERENTIAL_CONSTRAINTS","ROUTINES","SCHEMATA","STATISTICS","TABLES","TABLESPACES","TRIGGERS","VIEWS"};
            for (String t : infoTables) {
                List<String> row = new ArrayList<>();
                row.add(t.toLowerCase());
                row.add("MEMORY"); row.add("10"); row.add("Dynamic"); row.add("0");
                row.add("0"); row.add("0"); row.add("0"); row.add("0"); row.add("0");
                row.add(null); row.add(null); row.add(null); row.add(null);
                row.add("utf8mb4_0900_ai_ci"); row.add(null); row.add(""); row.add("");
                qr.rows.add(row);
            }
            return qr;
        }
        String schema = SqlRouter.toH2Schema(db);
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = session.conn().prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) tables.add(rs.getString(1)); }
        }
        for (String t : tables) {
            List<String> row = new ArrayList<>();
            row.add(t.toLowerCase());
            row.add("InnoDB"); row.add("10"); row.add("Dynamic"); row.add("0");
            row.add("0"); row.add("16384"); row.add("0"); row.add("0"); row.add("0");
            row.add("1"); row.add("2026-01-01 00:00:00"); row.add(null); row.add(null);
            row.add("utf8mb4_0900_ai_ci"); row.add(null); row.add(""); row.add("");
            qr.rows.add(row);
        }
        return qr;
    }

    static QueryResult showEngines() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Engine","Support","Comment","Transactions","XA","Savepoints"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        qr.rows.add(new ArrayList<>(Arrays.asList("InnoDB","DEFAULT","Supports transactions","YES","YES","YES")));
        return qr;
    }

    static QueryResult showCharset() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Charset","Description","Default collation","Maxlen"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        qr.rows.add(new ArrayList<>(Arrays.asList("utf8mb4","UTF-8 Unicode","utf8mb4_0900_ai_ci","4")));
        qr.rows.add(new ArrayList<>(Arrays.asList("utf8mb3","UTF-8 Unicode","utf8_general_ci","3")));
        qr.rows.add(new ArrayList<>(Arrays.asList("latin1","cp1252 West European","latin1_swedish_ci","1")));
        return qr;
    }

    static QueryResult showCollation() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Collation","Charset","Id","Default","Compiled","Sortlen"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        qr.rows.add(new ArrayList<>(Arrays.asList("utf8mb4_0900_ai_ci","utf8mb4","255","Yes","Yes","0")));
        qr.rows.add(new ArrayList<>(Arrays.asList("utf8_general_ci","utf8mb3","33","Yes","Yes","1")));
        return qr;
    }

    static QueryResult emptyProcesslist(Session session) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Id","User","Host","db","Command","Time","State","Info"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        qr.rows.add(new ArrayList<>(Arrays.asList(String.valueOf(session.connId), session.user, "localhost", session.currentDb, "Sleep", "0", "", null)));
        return qr;
    }

    static QueryResult emptyWarnings() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Level"); qr.colTypes.add(253);
        qr.colNames.add("Code"); qr.colTypes.add(3);
        qr.colNames.add("Message"); qr.colTypes.add(253);
        return qr;
    }

    static QueryResult emptyOpenTables() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Database","Table","In_use","Name_locked"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyGrants() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Grants for root@localhost"); qr.colTypes.add(253);
        qr.rows.add(new ArrayList<>(Collections.singletonList("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION")));
        return qr;
    }

    static QueryResult emptyRoutine(String type) {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Db","Name","Type","Definer","Modified","Created","Security_type","Comment","character_set_client","collation_connection","Database Collation"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyTriggers() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Trigger","Event","Table","Statement","Timing","Created","sql_mode","Definer","character_set_client","collation_connection","Database Collation"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyEvents() {
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Db","Name","Definer","Time zone","Type","Execute at","Interval value","Interval field","Starts","Ends","Status","Originator","character_set_client","collation_connection","Database Collation"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyRefConstraints() {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        String[] cols = {"CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","UNIQUE_CONSTRAINT_CATALOG","UNIQUE_CONSTRAINT_SCHEMA","UNIQUE_CONSTRAINT_NAME","MATCH_OPTION","UPDATE_RULE","DELETE_RULE","TABLE_NAME","REFERENCED_TABLE_NAME"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyStatisticsShowKeys() {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        String[] cols = {"Table","Non_unique","Key_name","Seq_in_index","Column_name","Collation","Cardinality","Sub_part","Packed","Null","Index_type","Comment","Index_comment","Visible","Expression"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyKeyColumnUsage() {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        String[] cols = {"CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","ORDINAL_POSITION","POSITION_IN_UNIQUE_CONSTRAINT","REFERENCED_TABLE_SCHEMA","REFERENCED_TABLE_NAME","REFERENCED_COLUMN_NAME"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyStatistics() {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        String[] cols = {"TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","NON_UNIQUE","INDEX_SCHEMA","INDEX_NAME","SEQ_IN_INDEX","COLUMN_NAME","COLLATION","CARDINALITY","SUB_PART","PACKED","NULLABLE","INDEX_TYPE","COMMENT","INDEX_COMMENT"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        return qr;
    }

    static QueryResult emptyInnodbStats() {
        QueryResult qr = new QueryResult(); qr.isResultset = true;
        qr.colNames.add("index_name"); qr.colTypes.add(253);
        qr.colNames.add("stat_value"); qr.colTypes.add(8);
        return qr;
    }
}
