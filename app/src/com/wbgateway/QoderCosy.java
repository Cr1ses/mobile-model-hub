package com.wbgateway;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Qoder cosy 协议：加密与签名原语。
 *
 * 协议实现参考 dsh-connect-qoder 的 src/cosy.ts（MIT）与
 * fengyinxia/qoder2api 的 qoder_auth.py，逐项对齐后在本机实测通过。
 *
 * 关键点（与常见误解不同）：
 *   1. 会话加密是 **AES-128-CBC**（不是 GCM），IV 直接等于密钥；
 *   2. `info` 是 base64(AES-CBC(identityJson))，不是 nonce 前缀拼接；
 *   3. `cosyKey` 是 base64(RSA-PKCS1v1.5(tempKey))，不是 OAEP；
 *   4. 签名是 md5(payloadB64 \n cosyKey \n date \n body \n pathSig)，
 *      密钥不参与——它是**确定性拼接哈希**，因此可纯 Java 复现；
 *   5. body 必须用 cosyEncode（标准 base64 → n/3 三块重排 → 自定义字母表）。
 */
public final class QoderCosy {

    /** cosy 自定义 base64 字母表。 */
    public static final String CUSTOM_ALPHABET =
        "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!";
    private static final String STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    /** 自定义填充符。 */
    public static final char CUSTOM_PAD = '$';

    /** Qoder 网关 RSA 公钥（1024 位，DER 的 base64）。 */
    public static final String SERVER_PUBKEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6P"
      + "v5Nc4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthY"
      + "A2BqGz+l6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/c"
      + "Px/3Vr/J6I17XcW+ML9FoCI6AOvOzwIDAQAB";

    /** cosy 客户端版本（身份的一部分，不能随意改）。 */
    public static final String COSY_VERSION = "0.1.43";

    private static final char[] S2C = new char[128];
    private static final char[] C2S = new char[128];

    static {
        for (int i = 0; i < 128; i++) {
            S2C[i] = 0;
            C2S[i] = 0;
        }
        for (int i = 0; i < 64; i++) {
            S2C[STD_ALPHABET.charAt(i)] = CUSTOM_ALPHABET.charAt(i);
            C2S[CUSTOM_ALPHABET.charAt(i)] = STD_ALPHABET.charAt(i);
        }
        S2C['='] = CUSTOM_PAD;
        C2S[CUSTOM_PAD] = '=';
    }

    private QoderCosy() {}

    // ---------- 自定义 base64 ----------

    /** 标准 base64 → n/3 三块重排 → 自定义字母表。 */
    public static String cosyEncode(byte[] plain) {
        String std = java.util.Base64.getEncoder().encodeToString(plain);
        int n = std.length();
        int a = n / 3;
        String rearranged = std.substring(n - a) + std.substring(a, n - a) + std.substring(0, a);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char ch = rearranged.charAt(i);
            char m = ch < 128 ? S2C[ch] : 0;
            if (m == 0) throw new IllegalArgumentException("cosy encode: 字符不在字母表内: " + ch);
            sb.append(m);
        }
        return sb.toString();
    }

    /** cosyEncode 的逆运算。 */
    public static byte[] cosyDecode(String encoded) {
        StringBuilder mapped = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            char s = ch < 128 ? C2S[ch] : 0;
            if (s == 0) throw new IllegalArgumentException("cosy decode: 字符不在自定义字母表内: " + ch);
            mapped.append(s);
        }
        String m = mapped.toString();
        int n = m.length();
        int a = n / 3;
        String std = m.substring(n - a) + m.substring(a, n - a) + m.substring(0, a);
        return java.util.Base64.getDecoder().decode(std);
    }

    // ---------- 加密原语 ----------

    /** AES-128-CBC + PKCS7，IV = key。 */
    public static byte[] aesCbcEncrypt(byte[] plain, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return c.doFinal(plain);
    }

    /** AES-128-CBC 解密。 */
    public static byte[] aesCbcDecrypt(byte[] enc, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return c.doFinal(enc);
    }

    /** RSA PKCS1v1.5 加密（网关公钥）。 */
    public static byte[] rsaEncrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE,
                java.security.KeyFactory.getInstance("RSA").generatePublic(
                        new java.security.spec.X509EncodedKeySpec(
                                java.util.Base64.getDecoder().decode(SERVER_PUBKEY_B64))));
        return c.doFinal(plain);
    }

    // ---------- 会话 ----------

    /** 一次 cosy 会话的全部签名要素。 */
    public static final class Session {
        public byte[] tempKey;
        public String cosyKey;
        public String info;
        String uid;
        String securityOauthToken;
        String machineId;
        String machineToken;
        String machineType;
    }

    /**
     * 新建会话：随机 16 字节临时密钥 → cosyKey / info。
     *
     * `machineId` 用账号真实值；设备指纹派生值在 CN 网关实测也能通过，
     * 但真实值更稳（见 QoderCosy.fingerprint 注释）。
     */
    public static Session newSession(String uid, String name, String securityOauthToken,
                              String machineId, String machineToken, String machineType)
            throws Exception {
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String hex = hex(rnd);
        byte[] tempKey = hex.substring(0, 16).getBytes("UTF-8");

        Session s = new Session();
        s.tempKey = tempKey;
        s.cosyKey = java.util.Base64.getEncoder().encodeToString(rsaEncrypt(tempKey));
        s.uid = uid;
        s.securityOauthToken = securityOauthToken;
        s.machineId = machineId;
        s.machineToken = machineToken;
        s.machineType = machineType;

        Map<String, String> id = new LinkedHashMap<String, String>();
        id.put("name", name == null ? "" : name);
        id.put("aid", uid);
        id.put("uid", uid);
        id.put("yx_uid", "");
        id.put("organization_id", "");
        id.put("organization_name", "");
        id.put("user_type", "personal_standard");
        id.put("security_oauth_token", securityOauthToken);
        id.put("refresh_token", "");
        s.info = java.util.Base64.getEncoder().encodeToString(
                aesCbcEncrypt(json(id).getBytes("UTF-8"), tempKey));
        return s;
    }

    /**
     * Bearer 载荷：键序字典序的 JSON，标准 base64。
     *
     * 键序必须字典序（cosyVersion < ideVersion < info < requestId < version），
     * 因为签名直接对这个 base64 串做哈希——顺序变了签名就错。
     */
    public static String buildPayloadB64(String info) {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("cosyVersion", COSY_VERSION);
        p.put("ideVersion", "");
        p.put("info", info);
        p.put("requestId", UUID.randomUUID().toString());
        p.put("version", "v1");
        return java.util.Base64.getEncoder().encodeToString(
                json(p).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 请求签名：md5(payloadB64\ncosyKey\ndate\nbody\npathSig)。 */
    public static String signRequest(String payloadB64, String cosyKey, String cosyDate,
                              String body, String pathSig) throws Exception {
        return md5Hex(payloadB64 + "\n" + cosyKey + "\n" + cosyDate + "\n" + body + "\n" + pathSig);
    }

    /** 组装 Authorization 头。 */
    public static String composeBearer(String payloadB64, String sig) {
        return "Bearer COSY." + payloadB64 + "." + sig;
    }

    /** 从完整 URL 派生签名路径：去掉 /algo 前缀，不含查询串。 */
    public static String pathSigFrom(String fullUrl) {
        String path;
        try {
            path = java.net.URI.create(fullUrl).getPath();
        } catch (Exception e) {
            path = fullUrl;
        }
        if (path == null) path = "";
        return path.startsWith("/algo") ? path.substring("/algo".length()) : path;
    }

    /** 当前 UTC 时间的 RFC1123 表示（cosy-date 头与签名都用它）。 */
    public static String rfc1123() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    // ---------- 设备指纹 ----------

    /** 由账号 uid 派生的稳定设备指纹（对应 cosy.ts 的 deriveFingerprint）。 */
    public static String[] fingerprint(String uid) throws Exception {
        String id = md5Hex("machine:" + uid);
        String type = md5Hex("machinetype:" + uid).substring(0, 18);
        String token = sha512B64Url("machinetoken:" + uid).substring(0, 43);
        return new String[] {id, type, token};
    }

    // ---------- 工具 ----------

    public static String json(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"")
              .append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String md5Hex(String s) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")));
    }

    public static String sha512B64Url(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-512").digest(s.getBytes("UTF-8"));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
