package mirage.core;

import java.sql.*;
import java.util.*;

/** Mirage - 表结构相关（SHOW CREATE TABLE / DESCRIBE / 类型映射）。从 SqlRouter 拆出。 */
public class MetaTable {
    static final String BQ = SqlRouter.BQ;

    static String extractTableName(String sql) {
        String s = sql.replaceAll("(?i)^\\s*(show\\s+(full\\s+)?create\\s+table|show\\s+(index|keys)\\s+(from|in)|describe|desc|show\\s+(full\\s+)?columns\\s+(from|in))\\s+", "").trim();
        s = s.replaceAll("[\\s;]+.*$", "").trim();
        s = s.replace(BQ, "").replace("\"", "").replace("'", "");
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(dot + 1);
        return s;
    }

    static QueryResult showCreateDatabase(String sql) {
        String name = sql.replaceAll("(?i)^\\s*show\\s+create\\s+(database|schema)\\s+", "")
                         .replace(BQ, "").replace(";", "").trim();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Database"); qr.colTypes.add(253);
        qr.colNames.add("Create Database"); qr.colTypes.add(253);
        List<String> row = new ArrayList<>();
        row.add(name);
        row.add("CREATE DATABASE " + BQ + name + BQ + " /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */");
        qr.rows.add(row);
        return qr;
    }

    static QueryResult showCreateTable(String tbl, Session session) throws SQLException {
        String schema = session.h2Schema();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        qr.colNames.add("Table"); qr.colTypes.add(253);
        qr.colNames.add("Create Table"); qr.colTypes.add(253);
        StringBuilder sb = new StringBuilder();
        boolean hasPk = false;
        StringBuilder pkCol = new StringBuilder();
        sb.append("CREATE TABLE ").append(BQ).append(tbl).append(BQ).append(" (\\n");
        try (PreparedStatement ps = session.conn().prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?) ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, tbl);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",\\n");
                    first = false;
                    boolean identity = false;
                    try { identity = rs.getBoolean(6); } catch (Exception ignore) {}
                    sb.append("  ").append(BQ).append(rs.getString(1).toLowerCase()).append(BQ).append(" ").append(toMysqlType(rs.getString(2), rs.getObject(3)));
                    if ("NO".equals(rs.getString(4))) sb.append(" NOT NULL");
                    if (identity) { sb.append(" AUTO_INCREMENT"); hasPk = true; pkCol.append(BQ).append(rs.getString(1).toLowerCase()).append(BQ); }
                    Object def = rs.getObject(5);
                    if (def != null) {
                        String ds = String.valueOf(def);
                        boolean numeric = ds.matches("-?\\d+(\\.\\d+)?");
                        boolean keyword = ds.toUpperCase().contains("CURRENT_TIMESTAMP") || ds.equalsIgnoreCase("NULL");
                        if (numeric || keyword) sb.append(" DEFAULT ").append(ds);
                        else sb.append(" DEFAULT '").append(ds.replace("'", "''")).append("'");
                    }
                }
            }
        }
        if (hasPk) sb.append(",\\n  PRIMARY KEY (").append(pkCol).append(")");
        sb.append("\\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        qr.rows.add(new ArrayList<>(Arrays.asList(tbl, sb.toString())));
        return qr;
    }

    static QueryResult describeTable(String tbl, Session session) throws SQLException {
        String schema = session.h2Schema();
        QueryResult qr = new QueryResult();
        qr.isResultset = true;
        String[] cols = {"Field","Type","Null","Key","Default","Extra"};
        for (String c : cols) { qr.colNames.add(c); qr.colTypes.add(253); }
        try (PreparedStatement ps = session.conn().prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT, IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?) ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, tbl);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String type = toMysqlType(rs.getString(2), rs.getObject(3));
                    String nullable = "YES".equals(rs.getString(4)) ? "YES" : "NO";
                    Object def = rs.getObject(5);
                    String defStr = def == null ? null : String.valueOf(def);
                    String extra = "";
                    try { if (rs.getBoolean(6)) extra = "auto_increment"; } catch (Exception ignore) {}
                    String key = extra.equals("auto_increment") ? "PRI" : "";
                    List<String> row = new ArrayList<>();
                    row.add(name); row.add(type); row.add(nullable); row.add(key); row.add(defStr); row.add(extra);
                    qr.rows.add(row);
                }
            }
        }
        return qr;
    }

    static String toMysqlBaseType(String h2type) {
        String t = h2type.toUpperCase();
        switch (t) {
            case "INTEGER": case "INT": return "int";
            case "BIGINT": return "bigint";
            case "SMALLINT": return "smallint";
            case "TINYINT": return "tinyint";
            case "BOOLEAN": case "BIT": return "tinyint";
            case "DECIMAL": case "NUMERIC": return "decimal";
            case "REAL": case "FLOAT": return "float";
            case "DOUBLE": return "double";
            case "DATE": return "date";
            case "TIME": return "time";
            case "TIMESTAMP": return "timestamp";
            case "VARCHAR": case "CHARACTER VARYING": return "varchar";
            case "CHAR": return "char";
            case "CLOB": case "TEXT": return "text";
            case "BLOB": case "BINARY": case "VARBINARY": return "blob";
            default: return t.toLowerCase();
        }
    }

    static String toMysqlType(String h2type, Object charLen) {
        String t = h2type.toUpperCase();
        switch (t) {
            case "INTEGER": case "INT": return "int";
            case "BIGINT": return "bigint";
            case "SMALLINT": return "smallint";
            case "TINYINT": return "tinyint";
            case "BOOLEAN": case "BIT": return "tinyint(1)";
            case "DECIMAL": case "NUMERIC": return "decimal";
            case "REAL": case "FLOAT": return "float";
            case "DOUBLE": return "double";
            case "DATE": return "date";
            case "TIME": return "time";
            case "TIMESTAMP": return "timestamp";
            case "VARCHAR": case "CHARACTER VARYING": return "varchar(" + (charLen == null ? "255" : charLen) + ")";
            case "CHAR": return "char(" + (charLen == null ? "1" : charLen) + ")";
            case "CLOB": case "TEXT": return "longtext";
            case "BLOB": case "BINARY": case "VARBINARY": return "blob";
            default: return t.toLowerCase();
        }
    }
}
