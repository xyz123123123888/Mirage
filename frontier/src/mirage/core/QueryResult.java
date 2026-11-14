package mirage.core;

import java.util.*;

public class QueryResult {
    public boolean isResultset = false;
    public boolean isError = false;
    public String errorMsg = "";
    public int errorCode = 1064;
    public String sqlState = "42000";
    public long affectedRows = 0;
    public long lastInsertId = 0;    // 自增主键最后插入值
    public String schema = "";       // 结果集所属库名
    public List<String> colNames = new ArrayList<>();
    public List<Integer> colTypes = new ArrayList<>();
    public List<List<String>> rows = new ArrayList<>();

    public static QueryResult error(int code, String state, String msg) {
        QueryResult q = new QueryResult();
        q.isError = true; q.errorCode = code; q.sqlState = state; q.errorMsg = msg;
        return q;
    }
}
