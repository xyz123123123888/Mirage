package mirage.core;

import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RSA 认证支持（caching_sha2_password full auth）。
 * 流程：服务端发 AuthMoreData(0x04) → 客户端请求公钥(0x02) →
 *       服务端发 PEM 公钥 → 客户端发 RSA(XOR(password+\0, nonce)) →
 *       服务端私钥解密 → 还原密码。
 */
public class RsaAuth {
    private static KeyPair keyPair;

    public static void init() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
    }

    public static String publicKeyPem() {
        byte[] der = keyPair.getPublic().getEncoded();
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
    }

    /** 解密 RSA 数据（先试 PKCS1 v1.5，失败再试 OAEP）。 */
    public static byte[] decrypt(byte[] encrypted) throws Exception {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            return c.doFinal(encrypted);
        } catch (Exception e1) {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            c.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            return c.doFinal(encrypted);
        }
    }

    /** 用两种 padding 都尝试解密，返回所有合理结果（避免 PKCS1 对 OAEP 密文偶然不抛异常产生垃圾）。
     *  只保留长度 < 128 字节的结果（密码不会那么长）。 */
    public static java.util.List<byte[]> decryptCandidates(byte[] encrypted) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            byte[] r = c.doFinal(encrypted);
            if (r != null && r.length > 0 && r.length < 128) out.add(r);
        } catch (Exception ignore) {}
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            c.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            byte[] r = c.doFinal(encrypted);
            if (r != null && r.length > 0 && r.length < 128) out.add(r);
        } catch (Exception ignore) {}
        return out;
    }

    /** 从解密数据还原密码：password = XOR(decrypted, nonce)，遇 NUL 终止符停止。
     *  返回所有 nonce 变体的候选（供调用方逐一校验，避免填充字节导致的误判）。 */
    public static java.util.List<String> recoverPasswordCandidates(byte[] decrypted, byte[] nonce) {
        java.util.List<byte[]> variants = new java.util.ArrayList<>();
        variants.add(nonce);
        byte[] n21 = new byte[nonce.length + 1]; System.arraycopy(nonce,0,n21,0,nonce.length); variants.add(n21);
        byte[] n8n12 = new byte[21]; System.arraycopy(nonce,0,n8n12,0,8); System.arraycopy(nonce,8,n8n12,9,12); variants.add(n8n12);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (byte[] n : variants) {
            byte[] pwd = new byte[decrypted.length];
            for (int i = 0; i < decrypted.length; i++) pwd[i] = (byte)(decrypted[i] ^ n[i % n.length]);
            // 遇到第一个 NUL 终止（密码是 null 结尾）
            int len = 0;
            while (len < pwd.length && pwd[len] != 0) len++;
            // 全可打印才算有效候选
            boolean printable = len > 0;
            for (int i = 0; i < len; i++) { int c = pwd[i]&0xFF; if (c < 32 || c > 126) { printable = false; break; } }
            if (printable) out.add(new String(pwd, 0, len, StandardCharsets.UTF_8));
        }
        return new java.util.ArrayList<>(out);
    }

    /** 兼容旧接口：返回首个候选。 */
    public static String recoverPassword(byte[] decrypted, byte[] nonce) {
        java.util.List<String> c = recoverPasswordCandidates(decrypted, nonce);
        return c.isEmpty() ? "" : c.get(0);
    }
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&0xFF));return s.toString();}
}
