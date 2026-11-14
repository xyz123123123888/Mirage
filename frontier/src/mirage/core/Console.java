package mirage.core;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 终端彩色日志。 */
public class Console {
    // ANSI 颜色
    private static final String RESET = "\u001B[0m";
    private static final String GRAY = "\u001B[90m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    static boolean color = true;
    static boolean toConsole = true;
    /** 最低输出级别：0=DEBUG 1=INFO 2=WARN 3=ERROR（来自 log.level）。 */
    static int minLevel = 1;

    static void setLevel(String name) {
        if (name == null) return;
        switch (name.trim().toUpperCase()) {
            case "DEBUG": minLevel = 0; break;
            case "INFO":  minLevel = 1; break;
            case "WARN":  minLevel = 2; break;
            case "ERROR": minLevel = 3; break;
            default: break;
        }
    }

    static void disableColor() { color = false; }

    private static String c(String code, String s) { return color ? code + s + RESET : s; }
    private static String ts() { return new SimpleDateFormat("HH:mm:ss").format(new Date()); }

    public static void banner(String name, String version, int port, String host, String db, boolean h2Legacy) {
        line();
        emit(c(BOLD + CYAN, "  " + name) + " " + c(GRAY, version));
        emit(c(GRAY, "  A MySQL-compatible proxy backed by H2."));
        line();
        info("监听地址", host + ":" + port);
        info("数据库", db + (h2Legacy ? " (H2 1.4.200)" : " (H2 2.x)"));
        info("Java", System.getProperty("java.version"));
        line();
    }

    private static void line() {
        emit(c(GRAY, "  ────────────────────────────────────────"));
    }

    private static void info(String k, String v) {
        emit("  " + c(GRAY, pad(k, 10)) + v);
    }

    /** 统一出口：MC 插件已注入 LogSink 时走宿主 logger，否则 System.out。 */
    private static void emit(String s) {
        LogSink.info(s);
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(" ");
        return sb.toString();
    }

    public static void info(String msg) { print(GREEN, "INFO", 1, msg); }
    public static void warn(String msg) { print(YELLOW, "WARN", 2, msg); }
    public static void error(String msg) { print(RED, "ERROR", 3, msg); }
    public static void debug(String msg) { print(GRAY, "DEBUG", 0, msg); }
    public static void conn(String msg) { print(BLUE, "CONN", 1, msg); }

    private static void print(String color, String level, int rank, String msg) {
        if (!toConsole || rank < minLevel) return;
        String lvl = color + pad(level, 5) + RESET;
        String line = c(GRAY, "[" + ts() + "] ") + lvl + " " + msg;
        // 按级别走 LogSink：MC 插件接管后不再触碰 System.out/err
        if (rank >= 3) LogSink.error(line);
        else if (rank >= 2) LogSink.warn(line);
        else LogSink.info(line);
    }
}
