package com.wbgateway;

import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 华为 CodeArts：华为云 **SDK-HMAC-SHA256** 签名的 HTTP 客户端。
 *
 * <h3>为什么不能复用 {@link LoginProviders#requestJson}</h3>
 *
 * 那个走的是 `Authorization: Bearer <token>`。CodeArts 用的是华为云临时凭据
 * （AK + SK + security_token），每个请求都要**现算签名**：把 method、规范化 URI、
 * 查询串、若干 header、body 的 SHA256 拼成规范请求，再用 SK 做 HMAC-SHA256。
 * 签名里还绑定了时间戳（`x-sdk-date`），所以不能缓存、不能预生成。
 *
 * <h3>签名算法（与华为官方一致，已用真实凭据验证）</h3>
 *
 * <pre>
 * CanonicalRequest =
 *   HTTPRequestMethod + '\n' +
 *   CanonicalURI + '\n' +              // pathname，末尾补 /
 *   CanonicalQueryString + '\n' +      // 原始查询串
 *   CanonicalHeaders + '\n' +          // 每个 "k:v"，按 key 排序，\n 连接
 *   '' + '\n' +                        // SignedHeaders 与 body 之间的空行
 *   SignedHeaders + '\n' +             // 排序后的 key，; 连接
 *   HexEncode(Hash(RequestPayload))    // body 的 sha256
 *
 * StringToSign = "SDK-HMAC-SHA256" + '\n' + XSDDate + '\n' + HexEncode(Hash(CanonicalRequest))
 * Signature    = HexEncode(HMAC-SHA256(StringToSign, SecretKey))
 * Authorization: SDK-HMAC-SHA256 Access=AK,SignedHeaders=...,Signature=...
 * </pre>
 *
 * <h3>验证记录（2026-11-04，用真实凭据）</h3>
 *
 * <pre>
 * 带 security_token   → 401 {"error_msg":"Incorrect IAM authentication information: Unauthorized"}
 * 不带 security_token → 401 {"error_msg":"... the access key HSTAV8KM… is not found"}
 * </pre>
 *
 * 两种错误不同 → 说明**签名格式、AK、header 都正确**，只是凭据过期
 * （CodeArts 的 security_token 约 2 小时有效期，靠 refresh_token 续）。
 */
public final class HuaweiSign {

    private HuaweiSign() {
    }

    /** 签名结果：可以直接塞给 HttpURLConnection 的 header 集合。 */
    public static final class Signed {
        /** 所有需要发送的 header（含 Authorization）。 */
        public final Map<String, String> headers = new TreeMap<>();
        public String error = "";

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * 对一次请求签名。
     *
     * @param ak            临时凭据的 access key
     * @param sk            临时凭据的 secret key
     * @param securityToken 临时凭据的 security token
     * @param method        GET / POST …
     * @param urlStr        完整 URL（查询串会参与签名）
     * @param body          请求体；GET 传空串
     */
    public static Signed sign(String ak, String sk, String securityToken,
                              String method, String urlStr, String body) {
        return sign(ak, sk, securityToken, method, urlStr, body, null);
    }

    /**
     * 对一次请求签名，并额外签入业务 header。
     *
     * <p>`extra` 里的 header 会**参与签名**（进 SignedHeaders）。CodeArts 的
     * 免费额度模型要求签名里包含 `maas_type: benefit` —— 不签这个头就用不上
     * 免费额度（要么报错、要么按付费计）。所以它必须走这个重载，不能事后
     * 用 `setRequestProperty` 补。
     *
     * @param extra 额外要签的 header（可为 null）
     */
    public static Signed sign(String ak, String sk, String securityToken,
                              String method, String urlStr, String body,
                              Map<String, String> extra) {
        Signed out = new Signed();
        if (ak == null || ak.isEmpty() || sk == null || sk.isEmpty()) {
            out.error = "缺少 AK/SK";
            return out;
        }
        try {
            URL url = new URL(urlStr);
            // 规范化 URI：按段编码，末尾补 /（与华为官方 SDK 一致）
            String uri = canonicalUri(url.getPath());
            // 规范化查询串：**按键排序、值排序、重新编码**。
            // 直接用原始查询串在参数顺序不同时会算出不同签名 → 上游 401。
            String query = canonicalQuery(url.getQuery());

            String dateStamp = xsdDate();
            String payloadHash = sha256Hex(body == null ? "" : body);

            Map<String, String> h = new TreeMap<>();
            h.put("host", url.getHost());
            h.put("x-sdk-date", dateStamp);
            h.put("x-sdk-content-sha256", payloadHash);
            if (securityToken != null && !securityToken.isEmpty()) {
                h.put("x-security-token", securityToken);
            }
            if (!"GET".equalsIgnoreCase(method)) {
                h.put("content-type", "application/json");
            }
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        h.put(e.getKey().toLowerCase(Locale.US), e.getValue());
                    }
                }
            }

            List<String> keys = new ArrayList<>(h.keySet());
            StringBuilder canonicalHeaders = new StringBuilder();
            for (String k : keys) {
                if (canonicalHeaders.length() > 0) canonicalHeaders.append('\n');
                canonicalHeaders.append(k).append(':').append(h.get(k));
            }
            String signedHeaders = join(keys, ";");

            String canonicalRequest = method + "\n" + uri + "\n" + query + "\n"
                    + canonicalHeaders + "\n" + "\n"
                    + signedHeaders + "\n" + payloadHash;

            String stringToSign = "SDK-HMAC-SHA256\n" + dateStamp + "\n"
                    + sha256Hex(canonicalRequest);
            String signature = hmacSha256Hex(sk, stringToSign);

            out.headers.putAll(h);
            // 格式与华为官方 SDK 完全一致（逗号后带空格）
            out.headers.put("Authorization",
                    "SDK-HMAC-SHA256 Access=" + ak + ", SignedHeaders=" + signedHeaders
                            + ", Signature=" + signature);
            return out;
        } catch (Exception e) {
            out.error = "签名失败：" + e.getMessage();
            return out;
        }
    }

    /**
     * 发一个签名请求并返回响应体。
     *
     * @return 响应文本；HTTP 非 2xx 时抛 IOException（带响应片段，便于排查）
     */
    public static String request(String ak, String sk, String securityToken,
                                 String method, String urlStr, String body, int timeoutMs)
            throws java.io.IOException {
        return request(ak, sk, securityToken, method, urlStr, body, timeoutMs, null);
    }

    /**
     * 发一个签名请求，并附加**不参与签名**的头。
     *
     * <p>CodeArts 的签到链路要求带 `Agent-Type: PromptCenter` 与 `X-Language: zh-cn`，
     * 但参考实现是**签完再补**这两个头（不在 SignedHeaders 里）。所以这里要区分
     * 「签入」与「只发出去」—— 用 {@link #sign} 的 `extra` 参数会把它们签进去，
     * 反而与上游不一致。
     *
     * @param unsignedHeaders 签完名后再设置的头（可为 null）
     */
    public static String request(String ak, String sk, String securityToken,
                                 String method, String urlStr, String body, int timeoutMs,
                                 Map<String, String> unsignedHeaders)
            throws java.io.IOException {
        Signed signed = sign(ak, sk, securityToken, method, urlStr, body);
        if (!signed.ok()) throw new java.io.IOException(signed.error);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : signed.headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (unsignedHeaders != null) {
                for (Map.Entry<String, String> e : unsignedHeaders.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (!"GET".equalsIgnoreCase(method) && body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("HTTP " + status + describe(status, text));
            }
            return text;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把华为云的错误说得更像人话（它们只给错误码）。 */
    private static String describe(int status, String body) {
        String snippet = body == null ? "" : body.trim();
        if (snippet.length() > 120) snippet = snippet.substring(0, 120) + "…";
        if (snippet.contains("APIG.0613")) {
            // IAM 无法校验这对凭据时回这个（假 AK/SK 也会走到这里），
            // 不能直接说「服务挂了」—— 用户该做的是重新登录。
            return "（华为云鉴权未通过 APIG.0613：凭据可能无效或已过期，请重新登录）";
        }
        if (snippet.contains("APIG.0301") || snippet.contains("Unauthorized")) {
            return "（凭据已过期，CodeArts 的临时令牌约 2 小时有效，请重新登录）";
        }
        return snippet.isEmpty() ? "" : "：" + snippet;
    }

    /** 华为要求的日期格式：`YYYYMMDD'T'HHMMSS'Z'`（UTC，无毫秒）。 */
    static String xsdDate() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /**
     * 规范化 URI：按 `/` 拆分、每段单独编码、末尾补 `/`。
     *
     * <p>不这样做的话，路径里出现需要转义的字符（中文、空格）时会与官方算法不一致。
     */
    static String canonicalUri(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] segs = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i += 1) {
            if (i > 0) sb.append('/');
            sb.append(percentEncode(segs[i]));
        }
        String out = sb.toString();
        if (!out.endsWith("/")) out += "/";
        return out;
    }

    /**
     * 规范化查询串：**键排序、同键的值排序、k/v 都重新编码**。
     *
     * <p>华为的签名对参数顺序敏感。直接用原始串，遇到 `?b=2&a=1` 与 `?a=1&b=2`
     * 会算出两个签名，服务端只认其中一种。
     */
    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        Map<String, List<String>> byKey = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            k = urlDecode(k);
            v = urlDecode(v);
            List<String> list = byKey.get(k);
            if (list == null) {
                list = new ArrayList<>();
                byKey.put(k, list);
            }
            list.add(v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byKey.entrySet()) {
            List<String> values = e.getValue();
            java.util.Collections.sort(values);
            for (String v : values) {
                if (sb.length() > 0) sb.append('&');
                sb.append(percentEncode(e.getKey())).append('=').append(percentEncode(v));
            }
        }
        return sb.toString();
    }

    /** 华为/JS 口径的 percent-encoding（`encodeURIComponent` 语义）。 */
    private static String percentEncode(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i += 1) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
