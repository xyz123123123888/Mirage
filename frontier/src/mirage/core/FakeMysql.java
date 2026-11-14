package mirage.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeMysql {
    static MirageConfig CONFIG;
    static PrintWriter logFile;
    static final Object lock = new Object();
    static final AtomicInteger connCounter = new AtomicInteger(100);
    static final SecureRandom RNG = new SecureRandom();

    /** 活跃客户端连接（connId -> socket），服务端关闭时用于主动断开。 */
    static final java.util.Map<Integer, Socket> OPEN_SOCKETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 独立运行入口（阻塞）。MC 插件请用 MirageServer.start()。 */
    public static void main(String[] args) throws Exception {
        MirageServer.start(null);
        // 保持主线程存活（独立运行时）
        Thread.currentThread().join();
    }

    /** 初始化日志文件（自动建目录）。 */
    static void initLogFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            logFile = new PrintWriter(new BufferedWriter(new FileWriter(f, true), 8192), false);
        } catch (Exception e) {
            LogSink.warn("[Mirage] 无法创建日志文件: " + e.getMessage());
        }
    }

    /** 低频日志（连接/认证/错误）——始终写文件（缓冲）。 */
    static void log(String msg) {
        synchronized (lock) {
            String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "] " + msg;
            if (logFile != null) { logFile.println(line); logFile.flush(); }
        }
    }

    /** 高频日志（每条 SQL）——默认关闭（配置控制）。 */
    static void logQuery(String msg) {
        if (CONFIG != null && !CONFIG.logQueries) return;
        log(msg);
    }

    static void handle(Socket s, int connId) {
        OPEN_SOCKETS.put(connId, s);
        Session session = Session.get(connId);
        Session.bind(session);
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            log("=== conn#" + connId + " from " + s.getRemoteSocketAddress() + " ===");

            // 1) 生成 scramble + 发握手
            // nonce 只用可打印 ASCII，避免客户端按 UTF-8 处理时被替换成 U+FFFD
            byte[] scramble = new byte[20];
            for (int i = 0; i < scramble.length; i++) scramble[i] = (byte)(0x21 + RNG.nextInt(0x5E));

            byte[] hs = buildHandshake(connId, scramble);
            writePacket(out, hs, 0);

            // 2) 读 HandshakeResponse
            Packet resp = readPacket(in);
            if (resp == null) { s.close(); return; }

            AuthInfo auth = parseHandshakeResponse(resp.payload);
            session.user = auth.user == null ? "root" : auth.user;
            log("[" + connId + "] auth request user=" + auth.user + " tokenLen=" + (auth.token==null?-1:auth.token.length));

            // 3) 认证：caching_sha2 (tokenLen==32) 走 full path (RSA)；native 走标准校验
            boolean useSha2 = auth.token != null && auth.token.length == 32;
            if (useSha2) {
                // 请求 full auth: AuthMoreData 包 = header(0x01) + data(0x04)
                writePacket(out, new byte[]{0x01, 0x04}, 2);
                // 读客户端下一包
                Packet pk = readPacket(in);
                if (pk == null) { s.close(); return; }
                byte[] pld = pk.payload;
                if (pld.length >= 1 && (pld[0] & 0xFF) == 0x02) {
                    // 客户端请求公钥
                    byte[] pem = RsaAuth.publicKeyPem().getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream pb = new ByteArrayOutputStream();
                    pb.write(0x01);                    // AuthMoreData header
                    pb.write(pem);                      // PEM（无长度前缀）
                    writePacket(out, pb.toByteArray(), (pk.seq + 1) & 0xFF);
                    pk = readPacket(in);
                    if (pk == null) { s.close(); return; }
                    pld = pk.payload;
                }
                // pld 应为 RSA 加密的密码
                try {
                    byte[] enc = pld;
                    java.util.List<byte[]> decs = RsaAuth.decryptCandidates(enc);
                    boolean ok = false;
                    int totalCands = 0;
                    for (byte[] dec : decs) {
                        for (String pwd : RsaAuth.recoverPasswordCandidates(dec, scramble)) {
                            totalCands++;
                            if (UserStore.checkPassword(session.user, pwd)) { ok = true; break; }
                        }
                        if (ok) break;
                    }
                    if (!ok) log("[FAIL-SHA2] conn=" + connId + " user=" + session.user + " decs=" + decs.size() + " cands=" + totalCands);
                    else log("[OK-SHA2] conn=" + connId);
                    if (!ok) {
                        writePacket(out, errPacket(1045, "28000", "Access denied for user '" + session.user + "'"), (pk.seq + 1) & 0xFF);
                        s.close();
                        return;
                    }
                    writePacket(out, okPacket(), (pk.seq + 1) & 0xFF);
                    log("[" + connId + "] AUTH OK (sha2 full) user=" + session.user);
                } catch (Exception ex) {
                    log("[FAIL-EX] conn=" + connId + " " + ex);
                    writePacket(out, errPacket(1045, "28000", "Auth error: " + ex.getMessage()), (pk.seq + 1) & 0xFF);
                    s.close();
                    return;
                }
            } else {
                boolean ok = UserStore.verifyNative(session.user, scramble, auth.token);
                if (!ok && UserStore.STRICT) {
                    log("[" + connId + "] AUTH FAILED (native) user=" + session.user);
                    writePacket(out, errPacket(1045, "28000", "Access denied for user '" + session.user + "'"), 2);
                    s.close();
                    return;
                }
                log("[" + connId + "] AUTH OK (native) user=" + session.user);
                writePacket(out, okPacket(), 2);
            }

            // 4) 命令循环
            while (true) {
                Packet p = readPacket(in);
                if (p == null) break;
                int cmd = p.payload[0] & 0xFF;
                int seq = (p.seq + 1) & 0xFF;
                if (cmd == 0x01) { log("COM_QUIT"); break; }
                if (cmd == 0x03) {
                    String sql = new String(p.payload, 1, p.payload.length - 1, StandardCharsets.UTF_8);
                    logQuery("[" + connId + "] Q: " + sql);
                    respond(out, sql, seq, session);
                } else if (cmd == 0x02) { // COM_INIT_DB
                    String db = new String(p.payload, 1, p.payload.length - 1, StandardCharsets.UTF_8).trim();
                    log("[" + connId + "] COM_INIT_DB -> " + db);
                    try { session.switchDb(db); writePacket(out, okPacket(), seq); }
                    catch (Exception e) { writePacket(out, errPacket(1049, "42000", "Unknown database '" + db + "'"), seq); }
                } else if (cmd == 0x16) { // COM_STMT_PREPARE（暂不支持）
                    log("[" + connId + "] COM_STMT_PREPARE (暂不支持)");
                    writePacket(out, errPacket(1295, "HY000",
                        "Mirage: server-side prepared statements not supported; use useServerPrepStmts=false"), seq);
                } else if (cmd == 0x17) { // COM_STMT_EXECUTE
                    log("[" + connId + "] COM_STMT_EXECUTE (暂不支持)");
                    writePacket(out, errPacket(1295, "HY000", "Mirage: server-side prepared statements not supported"), seq);
                } else if (cmd == 0x19) { // COM_STMT_CLOSE（无响应）
                    log("[" + connId + "] COM_STMT_CLOSE");
                } else if (cmd == 0x0E) {
                    writePacket(out, okPacket(), seq);
                } else if (cmd == 0x11) { // COM_CHANGE_USER
                    AuthInfo cu = parseChangeUser(p.payload);
                    session.user = cu.user;
                    log("[" + connId + "] COM_CHANGE_USER -> " + cu.user);
                    writePacket(out, okPacket(), seq);
                } else {
                    log("[" + connId + "] CMD 0x" + Integer.toHexString(cmd));
                    writePacket(out, okPacket(), seq);
                }
            }
            s.close();
            log("--- conn#" + connId + " closed ---");
        } catch (Exception e) {
            log("conn#" + connId + " error: " + e);
        } finally {
            OPEN_SOCKETS.remove(connId);
            Session.unbind();
            Session.remove(connId);
        }
    }

    /**
     * 主动断开所有客户端连接（服务端关闭流程第一步）。
     * 关闭 socket 会让阻塞在 read 上的处理线程立刻抛异常退出，从而停止产生新 SQL。
     */
    static void closeAllSockets() {
        int n = OPEN_SOCKETS.size();
        for (java.util.Map.Entry<Integer, Socket> e : new java.util.ArrayList<>(OPEN_SOCKETS.entrySet())) {
            try {
                e.getValue().close();
            } catch (Exception ignore) {}
        }
        OPEN_SOCKETS.clear();
        if (n > 0) log("[shutdown] 已断开 " + n + " 个客户端连接");
    }

    /** 冲刷并关闭日志文件。 */
    static void closeLogFile() {
        synchronized (lock) {
            if (logFile != null) {
                try { logFile.flush(); logFile.close(); } catch (Exception ignore) {}
                logFile = null;
            }
        }
    }

    static void respond(OutputStream out, String sql, int seq, Session session) throws IOException {
        QueryResult qr = SqlRouter.route(sql, session);
        if (qr.isError) {
            writePacket(out, errPacket(qr.errorCode, qr.sqlState, qr.errorMsg), seq);
            return;
        }
        if (qr.isResultset) {
            sendResultset(out, seq, qr);
        } else {
            writePacket(out, okPacketAffected(qr.affectedRows, qr.lastInsertId), seq);
        }
    }

    static void sendResultset(OutputStream out, int seq, QueryResult qr) throws IOException {
        int s = seq;
        // 全部包先写入缓冲，最后一次性 flush（大幅减少系统调用）
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeLenencInt(b, qr.colNames.size());
        writePacketTo(buf, b.toByteArray(), s++);
        for (int i = 0; i < qr.colNames.size(); i++) {
            writePacketTo(buf, columnDef(qr.colNames.get(i), qr.colTypes.get(i), qr.schema), s++);
        }
        writePacketTo(buf, eofPacket(), s++);
        for (List<String> row : qr.rows) {
            ByteArrayOutputStream rb = new ByteArrayOutputStream();
            for (String v : row) {
                if (v == null) rb.write(0xFB);
                else writeLenencStr(rb, v);
            }
            writePacketTo(buf, rb.toByteArray(), s++);
        }
        writePacketTo(buf, eofPacket(), s++);
        buf.writeTo(out);
        out.flush();
    }

    /** 把包写入缓冲区（不 flush）。 */
    static void writePacketTo(ByteArrayOutputStream buf, byte[] payload, int seq) {
        int len = payload.length;
        buf.write((byte)(len & 0xFF)); buf.write((byte)((len>>8)&0xFF)); buf.write((byte)((len>>16)&0xFF)); buf.write((byte)(seq & 0xFF));
        buf.write(payload, 0, payload.length);
    }

    static byte[] columnDef(String name, int type, String schema) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeLenencStr(b, "def");
        writeLenencStr(b, schema == null ? "" : schema);
        writeLenencStr(b, "");
        writeLenencStr(b, "");
        writeLenencStr(b, name);
        writeLenencStr(b, name);
        b.write(0x0c);
        b.write(0x21); b.write(0x00);
        writeInt4(b, 1024);
        b.write(type & 0xFF);
        writeInt2(b, 0);
        b.write(0x00);
        b.write(0x00); b.write(0x00);
        return b.toByteArray();
    }

    // ===== 认证包解析 =====
    static class AuthInfo { String user; byte[] token; int caps; }

    static AuthInfo parseHandshakeResponse(byte[] p) {
        AuthInfo a = new AuthInfo();
        try {
            int caps = (p[0]&0xFF) | ((p[1]&0xFF)<<8) | ((p[2]&0xFF)<<16) | ((p[3]&0xFF)<<24);
            a.caps = caps;
            int pos = 32; // 4 caps + 4 maxpkt + 1 charset + 23 reserved
            int end = pos;
            while (end < p.length && p[end] != 0) end++;
            a.user = new String(p, pos, end - pos, StandardCharsets.UTF_8);
            pos = end + 1;
            if ((caps & 0x00200000) != 0) { // CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
                long[] len = new long[1];
                pos = readLenenc(p, pos, len);
                int n = (int) len[0];
                a.token = Arrays.copyOfRange(p, pos, Math.min(pos + n, p.length));
            } else if ((caps & 0x00008000) != 0) { // CLIENT_SECURE_CONNECTION
                int n = p[pos] & 0xFF;
                a.token = Arrays.copyOfRange(p, pos + 1, Math.min(pos + 1 + n, p.length));
            } else {
                int e2 = pos;
                while (e2 < p.length && p[e2] != 0) e2++;
                a.token = Arrays.copyOfRange(p, pos, e2);
            }
        } catch (Exception e) {
            a.user = "root"; a.token = new byte[0];
        }
        return a;
    }

    static AuthInfo parseChangeUser(byte[] p) {
        AuthInfo a = new AuthInfo();
        try {
            int pos = 1;
            int end = pos;
            while (end < p.length && p[end] != 0) end++;
            a.user = new String(p, pos, end - pos, StandardCharsets.UTF_8);
            a.token = new byte[0];
        } catch (Exception e) { a.user = "root"; a.token = new byte[0]; }
        return a;
    }

    static int readLenenc(byte[] p, int pos, long[] out) {
        int b = p[pos] & 0xFF;
        if (b < 251) { out[0] = b; return pos + 1; }
        if (b == 0xFC) { out[0] = (p[pos+1]&0xFF) | ((p[pos+2]&0xFF)<<8); return pos + 3; }
        if (b == 0xFD) { out[0] = (p[pos+1]&0xFF) | ((p[pos+2]&0xFF)<<8) | ((p[pos+3]&0xFF)<<16); return pos + 4; }
        out[0] = 0; return pos + 9;
    }

    // ===== 包编码 =====
    static byte[] okPacket() { return new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00}; }
    static byte[] okPacketAffected(long affected, long lastInsertId) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x00);
        writeLenencInt(b, affected);
        writeLenencInt(b, lastInsertId);
        writeInt2(b, 0x0002);
        writeInt2(b, 0);
        return b.toByteArray();
    }
    static byte[] eofPacket() { return new byte[]{(byte)0xFE, 0x00, 0x00, 0x02, 0x00}; }
    static byte[] errPacket(String msg) { return errPacket(1064, "42000", msg); }
    static byte[] errPacket(int code, String state, String msg) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xFF);
        writeInt2(b, code);
        b.write(0x23);
        b.write('#');
        byte[] st = state.getBytes(StandardCharsets.UTF_8);
        b.write(st, 0, Math.min(5, st.length));
        byte[] m = msg.getBytes(StandardCharsets.UTF_8);
        b.write(m, 0, m.length);
        return b.toByteArray();
    }

    static void writeLenencInt(ByteArrayOutputStream b, long v) {
        if (v < 251) b.write((int) v);
        else if (v < 65536) { b.write(0xFC); b.write((int)(v & 0xFF)); b.write((int)((v >> 8) & 0xFF)); }
        else if (v < 16777216) { b.write(0xFD); b.write((int)(v & 0xFF)); b.write((int)((v >> 8) & 0xFF)); b.write((int)((v >> 16) & 0xFF)); }
        else { b.write(0xFE); for (int i = 0; i < 8; i++) b.write((int)((v >> (8*i)) & 0xFF)); }
    }
    static void writeLenencStr(ByteArrayOutputStream b, String s) {
        byte[] by = s.getBytes(StandardCharsets.UTF_8);
        writeLenencInt(b, by.length);
        b.write(by, 0, by.length);
    }

    static byte[] buildHandshake(int connId, byte[] scramble) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x0A);
        // 握手包中的服务端版本同样来自配置，保证与 SELECT VERSION() 一致
        String ver = (CONFIG != null && CONFIG.version != null && !CONFIG.version.isEmpty())
                ? CONFIG.version : "8.4.0-mirage";
        b.write((ver + "\0").getBytes(StandardCharsets.UTF_8));
        writeInt4(b, connId);
        b.write(scramble, 0, 8);
        b.write(0x00);
        int caps = 0x0009820D;
        writeInt2(b, caps & 0xFFFF);
        b.write(0x21);
        writeInt2(b, 0x0002);
        writeInt2(b, (caps >> 16) & 0xFFFF);
        b.write(21);
        b.write(new byte[10]);
        b.write(scramble, 8, 12);
        b.write(0x00);
        b.write("caching_sha2_password\0".getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    static void writeInt2(ByteArrayOutputStream b, int v) { b.write(v & 0xFF); b.write((v >> 8) & 0xFF); }
    static void writeInt4(ByteArrayOutputStream b, int v) { b.write(v & 0xFF); b.write((v >> 8) & 0xFF); b.write((v >> 16) & 0xFF); b.write((v >> 24) & 0xFF); }

    static void writePacket(OutputStream out, byte[] payload, int seq) throws IOException {
        int len = payload.length;
        int off = 0;
        // 超过 16MB 时拆分为多个包
        while (len - off >= MAX_PACKET) {
            out.write(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)(seq & 0xFF)});
            out.write(payload, off, MAX_PACKET);
            off += MAX_PACKET;
            seq = (seq + 1) & 0xFF;
        }
        int remain = len - off;
        out.write(new byte[]{(byte)(remain & 0xFF), (byte)((remain>>8)&0xFF), (byte)((remain>>16)&0xFF), (byte)(seq & 0xFF)});
        out.write(payload, off, remain);
        out.flush();
    }

    static final int MAX_PACKET = 0xFFFFFF;  // MySQL 单包上限 16MB

    /** 读取一个逻辑包：若为 16MB 满包则继续读取后续包并拼接（MySQL 分包机制）。 */
    static Packet readPacket(InputStream in) throws IOException {
        byte[] hdr = readN(in, 4);
        if (hdr == null) return null;
        int len = (hdr[0]&0xFF) | ((hdr[1]&0xFF)<<8) | ((hdr[2]&0xFF)<<16);
        int seq = hdr[3]&0xFF;
        if (len < MAX_PACKET) {
            byte[] payload = readN(in, len);
            if (payload == null) return null;
            return new Packet(seq, payload);
        }
        // 满包：继续读取后续分包
        ByteArrayOutputStream acc = new ByteArrayOutputStream(len * 2);
        acc.write(readN(in, len));
        while (true) {
            byte[] h2 = readN(in, 4);
            if (h2 == null) return null;
            int l2 = (h2[0]&0xFF) | ((h2[1]&0xFF)<<8) | ((h2[2]&0xFF)<<16);
            seq = h2[3]&0xFF;
            byte[] p2 = readN(in, l2);
            if (p2 == null) return null;
            acc.write(p2);
            if (l2 < MAX_PACKET) break;
        }
        return new Packet(seq, acc.toByteArray());
    }

    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    static class Packet { int seq; byte[] payload; Packet(int s, byte[] p){ seq=s; payload=p; } }
}
