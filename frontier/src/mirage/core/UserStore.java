package mirage.core;

import java.security.MessageDigest;
import java.util.*;

/**
 * 用户存储 + 认证（mysql_native_password / caching_sha2_password）。
 *
 * caching_sha2_password fast path（无需 RSA）：
 *   B = SHA256(SHA256(password))            —— 服务端存储
 *   token = SHA256(password) XOR SHA256(B || nonce)
 *   验证: A = token XOR SHA256(B || nonce); 检查 SHA256(A) == B
 */
public class UserStore {
    public static class User {
        public String name;
        public byte[] sha1Hash2;   // SHA1(SHA1(pwd))  —— native
        public byte[] sha256Hash2; // SHA256(SHA256(pwd)) —— caching_sha2
        public User(String n) { name = n; }
    }

    static final Map<String, User> users = new java.util.concurrent.ConcurrentHashMap<>();

    /** 严格模式：true 时真实校验密码；false 时宽松接受。 */
    public static boolean STRICT = true;

    /**
     * 从配置加载账号。
     * 出于安全考虑，仅支持单个账号；若配置里出现多个，只取第一个。
     */
    static void loadFromConfig(MirageConfig cfg) {
        STRICT = cfg.authStrict;
        users.clear();
        for (java.util.Map.Entry<String, String> e : cfg.users.entrySet()) {
            addUser(e.getKey(), e.getValue());
            break;  // 单账号：只取第一个
        }
    }

    public static void addUser(String name, String password) {
        User u = new User(name);
        u.sha1Hash2 = sha1(sha1(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        u.sha256Hash2 = sha256(sha256(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        users.put(name.toLowerCase(), u);
    }

    /** 明文密码校验（caching_sha2 full path 解密后用）。 */
    public static boolean checkPassword(String userName, String password) {
        User u = getUser(userName);
        if (u == null) return !STRICT;   // 宽松模式下未知用户放行
        byte[] h = sha256(sha256(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return Arrays.equals(h, u.sha256Hash2);
    }

    public static boolean hasUser(String name) { return users.containsKey(name.toLowerCase()); }
    public static User getUser(String name) { return users.get(name.toLowerCase()); }
    /** 已停用：仅支持单账号，运行时不再允许删除账号（保留以便回滚）。 */
    // public static void removeUser(String name) { users.remove(name.toLowerCase()); }

    public static List<String> userNames() {
        List<String> r = new ArrayList<>();
        for (User u : users.values()) r.add(u.name);
        return r;
    }

    // ===== caching_sha2_password fast path =====
    public static boolean verifySha2(String userName, byte[] nonce, byte[] token) {
        User u = getUser(userName);
        if (u == null) return false;
        byte[] B = u.sha256Hash2;
        if (token == null || token.length != 32) return false;
        // 尝试 20 字节和 21 字节（+尾随 null）两种 nonce
        byte[][] nonces = { nonce, concat(nonce, new byte[]{0}) };
        for (byte[] n : nonces) {
            byte[] c = sha256(concat(B, n));
            byte[] A = new byte[32];
            for (int i = 0; i < 32; i++) A[i] = (byte)(token[i] ^ c[i]);
            if (Arrays.equals(sha256(A), B)) return true;
        }
        return false;
    }

    // ===== mysql_native_password =====
    public static boolean verifyNative(String userName, byte[] scramble, byte[] token) {
        User u = getUser(userName);
        if (u == null) return false;
        byte[] B = u.sha1Hash2;
        if (token == null || token.length <= 1) {
            // 空密码：客户端发 0 或 1 字节
            return Arrays.equals(B, sha1(sha1(new byte[0])));
        }
        if (token.length != 20) return false;
        byte[] c = sha1(concat(scramble, B));
        byte[] A = new byte[20];
        for (int i = 0; i < 20; i++) A[i] = (byte)(token[i] ^ c[i]);
        return Arrays.equals(sha1(A), B);
    }

    /** 统一入口：严格模式下按协议校验；宽松模式直接放行（不登记新账号）。 */
    public static boolean verify(String userName, byte[] nonce, byte[] token, boolean sha2) {
        if (!STRICT) {
            // 单账号策略：宽松模式只放行，不再动态登记未知用户（旧代码保留注释）
            // if (!hasUser(userName)) addUser(userName, "");
            return true;
        }
        return sha2 ? verifySha2(userName, nonce, token) : verifyNative(userName, nonce, token);
    }

    static byte[] concat(byte[]... a) {
        int n = 0; for (byte[] x : a) n += x.length;
        byte[] r = new byte[n]; int p = 0;
        for (byte[] x : a) { System.arraycopy(x, 0, r, p, x.length); p += x.length; }
        return r;
    }
    static byte[] sha1(byte[] d) {
        try { return MessageDigest.getInstance("SHA-1").digest(d); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    static byte[] sha256(byte[] d) {
        try { return MessageDigest.getInstance("SHA-256").digest(d); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
