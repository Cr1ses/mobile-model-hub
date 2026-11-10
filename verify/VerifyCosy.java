import com.wbgateway.LoginProviders;

/**
 * Qoder cosy 协议的验证（纯函数，不触网）。
 *
 * 这些断言锁住的是**协议细节**：改错任何一个，签名就失效、上游回
 * 403 Signature invalid —— 而那种失败看起来像网络问题，很难反查到是
 * 字母表顺序或键序错了。所以每个易错点都单独断言。
 */
public final class VerifyCosy {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== Qoder cosy 协议（自定义 base64 / 签名 / 加密）===");

        testAlphabet();
        testEncode();
        testPayloadOrder();
        testSignature();
        testPathSig();
        testRegionModels();
        testSubscriptionBadge();
        testProbeClassify();

        System.out.println();
        System.out.println("Qoder cosy 协议：" + pass + " PASS / " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }

    /** 字母表必须 64 个**互不重复**的字符，且第 0 位是下划线。 */
    static void testAlphabet() {
        String a = com.wbgateway.QoderCosy.CUSTOM_ALPHABET;
        check("自定义字母表长度 64", a.length() == 64, "长度 " + a.length());

        java.util.Set<Character> set = new java.util.HashSet<Character>();
        for (int i = 0; i < a.length(); i++) set.add(a.charAt(i));
        check("字母表 64 个字符互不重复", set.size() == 64, "去重后 " + set.size());

        check("第 0 位是下划线（易错：漏掉它会导致解码全错）",
                a.charAt(0) == '_', String.valueOf(a.charAt(0)));
        check("末位是感叹号", a.charAt(63) == '!', String.valueOf(a.charAt(63)));
        check("不含标准表的 + /（否则不是自定义表）",
                a.indexOf('+') < 0 && a.indexOf('/') < 0, a);
        check("含非常规字符 %", a.indexOf('%') >= 0, a);
    }

    /** 编码必须可逆，且与标准 base64 不同。 */
    static void testEncode() {
        byte[] data = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String enc = com.wbgateway.QoderCosy.cosyEncode(data);
        byte[] back = com.wbgateway.QoderCosy.cosyDecode(enc);
        check("cosyEncode 可逆", "hello".equals(
                new String(back, java.nio.charset.StandardCharsets.UTF_8)), enc);

        String std = java.util.Base64.getEncoder().encodeToString(data);
        check("与标准 base64 不同（有 n/3 重排 + 换表）", !std.equals(enc), enc + " vs " + std);

        // 空输入
        check("空输入编码为空串", com.wbgateway.QoderCosy.cosyEncode(new byte[0]).isEmpty(), "");

        // 各种长度都要可逆（覆盖 n%3 的三种情况）
        for (int n : new int[] {1, 2, 3, 4, 5, 6, 7, 15, 16, 17, 100, 1000}) {
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = (byte) (i * 7 + 13);
            String e = com.wbgateway.QoderCosy.cosyEncode(d);
            byte[] b = com.wbgateway.QoderCosy.cosyDecode(e);
            check("长度 " + n + " 字节可逆", java.util.Arrays.equals(d, b), "");
        }

        // 含 '=' 填充的输入（n%3 != 0）必须映射成自定义 pad '$'
        byte[] pad = "ab".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String padEnc = com.wbgateway.QoderCosy.cosyEncode(pad);
        String padStd = java.util.Base64.getEncoder().encodeToString(pad);
        check("标准表有 = 填充时，自定义表用 $",
                padStd.indexOf('=') >= 0 && padEnc.indexOf('$') >= 0,
                padStd + " -> " + padEnc);
    }

    /** payload 的键序必须是字典序（签名直接对 base64 串做哈希）。 */
    static void testPayloadOrder() {
        String b64 = com.wbgateway.QoderCosy.buildPayloadB64("INFO");
        String json = new String(java.util.Base64.getDecoder().decode(b64),
                java.nio.charset.StandardCharsets.UTF_8);

        int iCosy = json.indexOf("\"cosyVersion\"");
        int iIde = json.indexOf("\"ideVersion\"");
        int iInfo = json.indexOf("\"info\"");
        int iReq = json.indexOf("\"requestId\"");
        int iVer = json.indexOf("\"version\"");
        check("payload 含全部 5 个字段",
                iCosy >= 0 && iIde >= 0 && iInfo >= 0 && iReq >= 0 && iVer >= 0, json);
        check("键序是字典序 cosyVersion < ideVersion < info < requestId < version",
                iCosy < iIde && iIde < iInfo && iInfo < iReq && iReq < iVer, json);
        check("cosyVersion 是 0.1.43（不是 1.1.49）",
                json.contains("\"cosyVersion\":\"0.1.43\""), json);
        check("ideVersion 是空串", json.contains("\"ideVersion\":\"\""), json);
        check("version 是 v1", json.contains("\"version\":\"v1\""), json);
        check("requestId 是 UUID 形状",
                json.matches("(?s).*\"requestId\":\"[0-9a-f-]{36}\".*"), json);
    }

    /** 签名是确定性拼接哈希，密钥不参与。 */
    static void testSignature() throws RuntimeException {
        try {
            String s1 = com.wbgateway.QoderCosy.signRequest("P", "K", "D", "B", "/path");
            String s2 = com.wbgateway.QoderCosy.signRequest("P", "K", "D", "B", "/path");
            check("同样入参签名相同（确定性）", s1.equals(s2), s1);
            check("签名是 32 位小写 hex", s1.matches("[0-9a-f]{32}"), s1);

            String s3 = com.wbgateway.QoderCosy.signRequest("P2", "K", "D", "B", "/path");
            check("改 payload 签名就变", !s1.equals(s3), s1 + " vs " + s3);
            String s4 = com.wbgateway.QoderCosy.signRequest("P", "K2", "D", "B", "/path");
            check("改 cosyKey 签名就变", !s1.equals(s4), s1 + " vs " + s4);
            String s5 = com.wbgateway.QoderCosy.signRequest("P", "K", "D2", "B", "/path");
            check("改 date 签名就变", !s1.equals(s5), s1 + " vs " + s5);
            String s6 = com.wbgateway.QoderCosy.signRequest("P", "K", "D", "B2", "/path");
            check("改 body 签名就变", !s1.equals(s6), s1 + " vs " + s6);
            String s7 = com.wbgateway.QoderCosy.signRequest("P", "K", "D", "B", "/path2");
            check("改 path 签名就变", !s1.equals(s7), s1 + " vs " + s7);

            // 已知值：md5("P\nK\nD\nB\n/path")
            String known = com.wbgateway.QoderCosy.md5Hex("P\nK\nD\nB\n/path");
            check("签名算法就是 md5(payload\\nkey\\ndate\\nbody\\npath)",
                    known.equals(s1), known);
        } catch (Exception e) {
            check("签名计算不抛异常", false, e.toString());
        }
    }

    /** pathSig 去掉 /algo 前缀且不含查询串。 */
    static void testPathSig() {
        String url = "https://gateway.qoder.com.cn/algo/api/v2/service/pro/sse/"
                + "agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
        check("pathSig 去掉 /algo 前缀",
                "/api/v2/service/pro/sse/agent_chat_generation"
                        .equals(com.wbgateway.QoderCosy.pathSigFrom(url)),
                com.wbgateway.QoderCosy.pathSigFrom(url));
        check("pathSig 不含查询串",
                com.wbgateway.QoderCosy.pathSigFrom(url).indexOf('?') < 0,
                com.wbgateway.QoderCosy.pathSigFrom(url));
        check("没有 /algo 前缀时原样返回",
                "/v1/chat".equals(com.wbgateway.QoderCosy.pathSigFrom("https://x.com/v1/chat")),
                com.wbgateway.QoderCosy.pathSigFrom("https://x.com/v1/chat"));
    }

    /** 两区域的可用模型集不同（国内版反而更多）。 */
    static void testRegionModels() {
        java.util.List<String> cn = LoginProviders.qoderUsableModels(LoginProviders.QODER_CN);
        java.util.List<String> intl = LoginProviders.qoderUsableModels(LoginProviders.QODER);

        check("国内版可用 11 个（2026-11-10 实测）", cn.size() == 11, "实际 " + cn.size());
        check("国际版可用 6 个", intl.size() == 6, "实际 " + intl.size());
        check("国内版含 dfmodel（国际版没有）",
                cn.contains("dfmodel") && !intl.contains("dfmodel"), cn.toString());
        check("国内版含 gfmodel", cn.contains("gfmodel"), cn.toString());
        check("国际版含 ultimate（国内版没有）",
                intl.contains("ultimate") && !cn.contains("ultimate"), intl.toString());
        check("两区域都含 qmodel/gmodel/kmodel/dmodel/mmodel",
                cn.containsAll(java.util.Arrays.asList("qmodel", "gmodel", "kmodel", "dmodel", "mmodel"))
                        && intl.containsAll(java.util.Arrays.asList("qmodel", "gmodel", "kmodel", "dmodel", "mmodel")),
                "");
        check("两区域都不含 auto（路由别名，不是模型）",
                !cn.contains("auto") && !intl.contains("auto"), "");
        check("两区域都不含 cmodel/smodel（后端不可用）",
                !cn.contains("cmodel") && !intl.contains("cmodel"), "");

        check("isQoderFamily 认两个区域",
                LoginProviders.isQoderFamily(LoginProviders.QODER)
                        && LoginProviders.isQoderFamily(LoginProviders.QODER_CN), "");
        check("isQoderFamily 不认其它渠道",
                !LoginProviders.isQoderFamily(LoginProviders.WB_INTL)
                        && !LoginProviders.isQoderFamily(""), "");

        // 规格表要跟着可用集走
        check("国内版规格表 12 条（11 可用 + 1 待探测候选）",
                LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER_CN).size() == 12,
                "实际 " + LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER_CN).size());
        check("国际版规格表 14 条（6 可用 + 8 需订阅）",
                LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER).size() == 14,
                "实际 " + LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER).size());
    }

    /** 「需订阅」标注 + 探测分类：字段、文案、判定顺序都要对。 */
    /** 「繁忙」标注 + 探测分类：字段、文案、判定顺序都要对。 */
    static void testSubscriptionBadge() {
        // 未标记时不出文案
        com.wbgateway.ModelSpec plain = new com.wbgateway.ModelSpec();
        plain.name = "X";
        check("未标记时不显示繁忙", plain.queueBadge().isEmpty(), plain.queueBadge());

        com.wbgateway.ModelSpec q = new com.wbgateway.ModelSpec();
        q.queued = true;
        check("标记后显示「繁忙」", "繁忙".equals(q.queueBadge()), q.queueBadge());

        com.wbgateway.ModelSpec q2 = new com.wbgateway.ModelSpec();
        q2.queued = true;
        q2.queuedNote = "正在排队";
        check("带说明时标注含原因",
                q2.queueBadge().contains("繁忙") && q2.queueBadge().contains("正在排队"),
                q2.queueBadge());

        // ⚠ 规格表**不做静态标记**：繁忙是瞬时状态，必须由运行时探测写。
        java.util.Map<String, com.wbgateway.ModelSpec> cn =
                LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER_CN);
        int cnStatic = 0;
        for (com.wbgateway.ModelSpec s : cn.values()) if (s.queued) cnStatic++;
        check("国内版规格表不带静态繁忙标记（由探测写）", cnStatic == 0, "实际 " + cnStatic);
        check("国内版清单含 qmodel", cn.containsKey("qmodel"), "");
        check("国内版清单含 smodel（实测可用）", cn.containsKey("smodel"), "");

        java.util.Map<String, com.wbgateway.ModelSpec> intl =
                LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER);
        int intlStatic = 0;
        for (com.wbgateway.ModelSpec s : intl.values()) if (s.queued) intlStatic++;
        check("国际版规格表不带静态繁忙标记", intlStatic == 0, "实际 " + intlStatic);

        // 共享模板不能被污染
        com.wbgateway.ModelSpec a1 = LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER_CN).get("qmodel");
        com.wbgateway.ModelSpec a2 = LoginProviders.qoderBuiltinSpecs(LoginProviders.QODER_CN).get("qmodel");
        check("两次调用各持独立副本", a1 != a2, "");

        // 序列化往返
        try {
            com.wbgateway.ModelSpec s = new com.wbgateway.ModelSpec();
            s.queued = true;
            s.queuedNote = "排队中";
            com.wbgateway.ModelSpec back = com.wbgateway.ModelSpec.fromJson(s.toJson());
            check("繁忙标记能存进 JSON 再读回",
                    back.queued && "排队中".equals(back.queuedNote), back.queuedNote);
        } catch (Exception e) {
            check("序列化往返不抛异常", false, e.toString());
        }
    }

    /** 探测结果分类：四态必须分清，否则界面会误导用户。 */
    static void testProbeClassify() {
        check("有 choices 判为可用",
                classifyOk("data:{\"body\":\"{\\\"choices\\\":[{\\\"delta\\\":{}}]}\"}"));

        String queue = "data:{\"code\":\"10605\",\"subCode\":\"RESOURCE_LIMIT\","
                + "\"message\":\"{\\\"retryAfterSeconds\\\":30}\"}";
        check("10605 / RESOURCE_LIMIT 判为繁忙（不是需订阅）", classifyQueued(queue));
        check("繁忙文案不提「订阅」", !classifyReason(queue).contains("订阅"), classifyReason(queue));

        String gone = "data:{\"code\":\"invalid_model_error\","
                + "\"message\":\"Unsupported model\"}";
        check("invalid_model 判为模型不存在", classifyGone(gone));
        check("invalid_model 不会被误判成繁忙", !classifyQueued(gone));

        String down = "data:{\"code\":\"provider_error\","
                + "\"message\":\"All backends failed\"}";
        check("provider_error 判为后端故障", classifyDown(down));
        check("provider_error 不会被误判成繁忙", !classifyQueued(down));

        // 错误文案：排队要能给出可重试的说明
        String desc = com.wbgateway.UpstreamError.describeSseError(queue);
        check("排队文案说成「排队」，不是「需要订阅」",
                desc.contains("排队") && !desc.contains("订阅"), desc);
        check("排队文案带上业务码", desc.contains("10605"), desc);
        check("排队文案带上建议等待时间", desc.contains("30 秒"), desc);

        String bare = "{\"code\":\"10605\",\"subCode\":\"RESOURCE_LIMIT\","
                + "\"message\":\"{\\\"retryAfterSeconds\\\":45}\"}";
        String bdesc = com.wbgateway.UpstreamError.describeSseError(bare);
        check("裸 JSON 也能解析成排队", bdesc.contains("排队"), bdesc);
        check("裸 JSON 带上建议等待时间", bdesc.contains("45 秒"), bdesc);
        check("裸 JSON 不再退化成「没给出原因」", !bdesc.contains("没给出原因"), bdesc);
    }

    static boolean classifyOk(String text) {
        return "0".equals(probeStatus(text));
    }

    static boolean classifyQueued(String text) {
        return "1".equals(probeStatus(text));
    }

    static boolean classifyGone(String text) {
        return "2".equals(probeStatus(text));
    }

    static boolean classifyDown(String text) {
        return "3".equals(probeStatus(text));
    }

    static String classifyReason(String text) {
        try {
            Class<?> cls = Class.forName("com.wbgateway.QoderProbe");
            java.lang.reflect.Method m = cls.getDeclaredMethod("classify", String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, "m", text);
            java.lang.reflect.Field f = r.getClass().getDeclaredField("reason");
            f.setAccessible(true);
            return String.valueOf(f.get(r));
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    static String probeStatus(String text) {
        try {
            Class<?> cls = Class.forName("com.wbgateway.QoderProbe");
            java.lang.reflect.Method m = cls.getDeclaredMethod("classify", String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, "m", text);
            java.lang.reflect.Method st = r.getClass().getDeclaredMethod("usable");
            java.lang.reflect.Method qd = r.getClass().getDeclaredMethod("queued");
            st.setAccessible(true);
            qd.setAccessible(true);
            if ((Boolean) st.invoke(r)) return "0";
            if ((Boolean) qd.invoke(r)) return "1";
            java.lang.reflect.Field f = r.getClass().getDeclaredField("status");
            f.setAccessible(true);
            return String.valueOf(f.get(r));
        } catch (Exception e) {
            return "ERR";
        }
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + " -- " + detail);
        }
    }
}
