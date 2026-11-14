package mirage.core;

import java.sql.Timestamp;

/**
 * 注册到 H2 的 MySQL 兼容函数。
 * 全部以 "FOR \"mirage.core.MySqlFunctions.xxx\"" 方式引用，
 * 避免 H2 在运行时动态编译 Java 源码（Java 25 下会产生 class 69，
 * H2 自身 ClassReader 不支持，报 Unsupported class file major version 69）。
 */
public class MySqlFunctions {

    /** REVERSE(s) */
    public static String reverse(String s) {
        return s == null ? null : new StringBuilder(s).reverse().toString();
    }

    /** SUBSTRING_INDEX(s, delim, count) */
    public static String substringIndex(String s, String delim, int count) {
        if (s == null || delim == null || delim.isEmpty()) return s;
        String[] parts = s.split(java.util.regex.Pattern.quote(delim), -1);
        if (count > 0) {
            if (count >= parts.length) return s;
            return String.join(delim, java.util.Arrays.copyOfRange(parts, 0, count));
        } else {
            int n = -count;
            if (n >= parts.length) return s;
            return String.join(delim, java.util.Arrays.copyOfRange(parts, parts.length - n, parts.length));
        }
    }

    /** FIELD(target, list...) */
    public static int field(String target, String... list) {
        if (target == null) return 0;
        for (int i = 0; i < list.length; i++) {
            if (target.equals(list[i])) return i + 1;
        }
        return 0;
    }

    /** DATE(ts) */
    public static Timestamp date(Timestamp ts) {
        return ts == null ? null : Timestamp.valueOf(ts.toLocalDateTime().toLocalDate().atStartOfDay());
    }

    /** TIME(ts) */
    public static java.sql.Time time(Timestamp ts) {
        return ts == null ? null : java.sql.Time.valueOf(ts.toLocalDateTime().toLocalTime());
    }

    /** MySQL DATE_FORMAT(ts, '%Y-%m-%d')。格式符：%Y %y %m %c %d %e %H %h %i %s %M %b %W %a %T %p %% */
    public static String dateFormat(Timestamp ts, String fmt) {
        if (ts == null) return null;
        String j = fmt.replace("%Y","yyyy").replace("%y","yy")
                .replace("%m","MM").replace("%c","M")
                .replace("%d","dd").replace("%e","d")
                .replace("%H","HH").replace("%h","hh")
                .replace("%i","mm").replace("%s","ss")
                .replace("%M","MMMM").replace("%b","MMM")
                .replace("%W","EEEE").replace("%a","EEE")
                .replace("%T","HH:mm:ss").replace("%p","a")
                .replace("%%","%");
        try {
            return new java.text.SimpleDateFormat(j).format(ts);
        } catch (Exception e) {
            return fmt;
        }
    }
}
