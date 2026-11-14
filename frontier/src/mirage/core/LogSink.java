package mirage.core;

/**
 * 日志出口（可注入）。
 * MC 插件通过 setHandler 把日志接到 plugin.getLogger()，
 * 避免使用 System.out/err 被服务端标为 ERROR 或触发 Nag 警告。
 * 独立运行时 handler 为 null，回退到 System.out/err。
 */
public final class LogSink {
    private LogSink() {}

    public interface Handler {
        void info(String msg);
        void warn(String msg);
        void error(String msg);
    }

    private static volatile Handler handler;

    /** 最低输出级别：0=DEBUG 1=INFO 2=WARN 3=ERROR。 */
    private static volatile int minLevel = 1;

    public static void setHandler(Handler h) { handler = h; }

    public static void setLevel(String name) {
        if (name == null) return;
        switch (name.trim().toUpperCase()) {
            case "DEBUG": minLevel = 0; break;
            case "INFO":  minLevel = 1; break;
            case "WARN":  minLevel = 2; break;
            case "ERROR": minLevel = 3; break;
            default: break;
        }
    }

    public static void info(String msg) {
        if (minLevel > 1) return;
        Handler h = handler;
        if (h != null) { h.info(msg); return; }
        System.out.println(msg);
    }

    public static void warn(String msg) {
        if (minLevel > 2) return;
        Handler h = handler;
        if (h != null) { h.warn(msg); return; }
        System.out.println(msg);
    }

    public static void error(String msg) {
        if (minLevel > 3) return;
        Handler h = handler;
        if (h != null) { h.error(msg); return; }
        System.err.println(msg);
    }
}
