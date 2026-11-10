package com.wbgateway;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型元数据表：把上游 id 翻译成「人话」。
 *
 * ## 为什么需要
 *
 * 上游的模型 id 对人不友好，界面上只看到 `model-a` 或
 * `glm-5.3-flash` 根本判断不了该用哪个：
 *
 * ```text
 * ❌ ☑ cmodel          ← Cantus？上下文多少？能看图吗？全靠猜
 * ✅ ☑ Cantus   Qoder 专用 · 上下文 180K · 输出 32K · 推理 xhigh… · 支持图片
 * ```
 *
 * 表里的数值来自桌面版插件里**已实测**的模型定义
 * （`workbuddyModels` / `qoderModels` / `raccoonModels` / `zcodeModels`），
 * 不是猜的：上下文窗口、最大输出、推理档位、是否支持图片都是上游真实声明。
 *
 * ## 查不到怎么办
 *
 * 用户自己加的代理渠道（DeepSeek 官方、硅基流动…）模型名上游会直接给，
 * 这里查不到就**原样显示 id** —— 不编造元数据，也不隐藏该行。
 *
 * @author modelhub
 */
public final class ModelMeta {

    /** 一条模型信息。 */
    public static final class Info {
        /** 展示名（人类可读）。 */
        public final String name;
        /** 上下文窗口（token），0 表示未知。 */
        public final int contextWindow;
        /** 最大输出（token），0 表示未知。 */
        public final int maxOutput;
        /** 是否支持图片输入。 */
        public final boolean supportsImages;
        /** 推理档位；空表示非推理模型。 */
        public final List<String> efforts;

        Info(String name, int contextWindow, int maxOutput, boolean supportsImages, List<String> efforts) {
            this.name = name;
            this.contextWindow = contextWindow;
            this.maxOutput = maxOutput;
            this.supportsImages = supportsImages;
            this.efforts = efforts;
        }

        /** 一句话规格，如「上下文 1M · 输出 128K · 推理 low/high · 支持图片」。 */
        public String specLine() {
            StringBuilder sb = new StringBuilder();
            if (contextWindow > 0) sb.append("上下文 ").append(shortTokens(contextWindow));
            if (maxOutput > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("输出 ").append(shortTokens(maxOutput));
            }
            if (efforts != null && !efforts.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("推理 ").append(joinEfforts(efforts));
            }
            if (supportsImages) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("支持图片");
            }
            return sb.toString();
        }

        private static String joinEfforts(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(list.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * 把 token 数写成 128K / 1M 这种好读的形式。
     *
     * <p>用 **1024 进制**：模型窗口本身是 2 的幂（131072 = 128×1024），
     * 用 1000 进制会显示成 `131K`，既不是常见写法也不好看。
     * 上游给的值多数是「人类的整数」如 200000 / 1000000，那时仍按 1024 算会得到
     * `195K` —— 所以做个折中：**先看能不能被 1024 整除**，能就按 1024 报，
     * 不能就按 1000 报（这样 200000 → 200K、131072 → 128K，两者都符合直觉）。
     */
    public static String shortTokens(int n) {
        if (n <= 0) return "—";
        // 2 的幂形态（131072 = 128×1024）优先按 1024 报 → 128K
        if (n % 1024 == 0 && n >= 1024) {
            int k = n / 1024;
            return (k % 1024 == 0 ? (k / 1024) + "M" : k + "K");
        }
        // 人类整数形态（200000 / 1000000）按 1000 报 → 200K / 1M
        if (n >= 1000000) {
            double m = n / 1000000.0;
            return (m == Math.floor(m) ? String.valueOf((int) m) : String.valueOf(m)) + "M";
        }
        if (n >= 1000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    /** id → 元信息。 */
    private static final Map<String, Info> TABLE = new HashMap<>();

    private static void m(String id, String name, int ctx, int out, boolean img, String efforts) {
        List<String> list = efforts == null || efforts.isEmpty()
                ? java.util.Collections.<String>emptyList()
                : Arrays.asList(efforts.split(","));
        TABLE.put(id, new Info(name, ctx, out, img, list));
    }

    static {
        // —— WorkBuddy 国际版 / 国内版（两区域并集，重复 id 已合并）——
        m("auto", "Auto", 200000, 32768, true, null);
        m("balanced-model", "Balanced", 256000, 32000, true, "medium");
        m("primary-model", "Primary", 272000, 72000, true, "high");
        m("deep-model", "Deep", 176000, 24000, true, null);
        m("fast-model", "Fast", 200000, 32000, true, "medium");
        m("default-model", "Auto（旧版别名）", 176000, 24000, true, null);
        m("gpt-6-astra", "GPT-6 Astra", 1000000, 128000, true, "low,medium,high,xhigh,max");
        m("gpt-5.6-sol", "GPT-5.6 Sol", 1000000, 128000, true, "low,medium,high,xhigh,max");
        m("gpt-5.6-terra", "GPT-5.6 Terra", 1000000, 128000, true, "low,medium,high,xhigh,max");
        m("gpt-5.6-luna", "GPT-5.6 Luna", 1000000, 128000, true, "low,medium,high,xhigh,max");
        m("gpt-5.5", "GPT-5.5", 1000000, 128000, true, "low,medium,high,xhigh");
        m("gpt-5.4", "GPT-5.4", 272000, 72000, true, "low,medium,high,xhigh");
        m("gpt-5.3-codex", "GPT-5.3 Codex", 272000, 0, true, "medium");
        m("grok-4.7", "Grok 4.7", 500000, 128000, true, "low,medium,high,xhigh");
        m("gemini-3.5-flash", "Gemini 3.5 Flash", 1000000, 65536, true, "medium");
        m("glm-5.3", "GLM-5.3", 1000000, 64000, true, "low,high,max");
        m("glm-5.3-flash", "GLM-5.3 Flash", 1000000, 32000, true, "low,high,max");
        m("glm-5.2", "GLM-5.2", 1000000, 64000, true, "high,xhigh");
        m("glm-5.1", "GLM-5.1", 200000, 48000, true, "medium");
        m("glm-5v-turbo", "GLM-5v Turbo", 200000, 64000, true, "medium");
        m("deepseek-v4-pro", "DeepSeek V4 Pro", 1000000, 128000, true, "low,high,xhigh");
        m("deepseek-v4-flash", "DeepSeek V4 Flash", 1000000, 50000, true, "low,high,max");
        m("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1000000, 128000, true, "low,high,max");
        m("deepseek-v4.1-flash-sg", "DeepSeek V4.1 Flash（新加坡）", 1000000, 128000, true, "high");
        m("kimi-k3", "Kimi K3", 1000000, 32000, true, "medium");
        m("kimi-k3-1", "Kimi K3（国内通道）", 1000000, 32000, true, "medium");
        m("kimi-k2.8-preview", "Kimi K2.8 Preview", 1000000, 64000, true, "low,high,max");
        m("kimi-k2.7", "Kimi K2.7 Code", 256000, 32000, true, "medium");
        m("kimi-k2.6", "Kimi K2.6", 256000, 32000, true, "medium");
        m("minimax-m3", "MiniMax M3", 512000, 64000, true, "medium");
        m("minimax-m2.7", "MiniMax M2.7", 200000, 48000, true, "medium");
        m("hy4-preview-f", "Hy4 preview（免费）", 1000000, 64000, true, "high");
        m("hy4-preview", "Hy4 preview（Pro）", 1000000, 64000, true, "high");
        m("hy3", "Hy3", 192000, 64000, true, "low,high");
        m("hy3-x", "Hy3（x）", 192000, 64000, true, "low,high");
        m("step-5-preview", "Step 5 Preview", 1000000, 64000, true, "low,medium,high");
        m("space-bunny", "Space Bunny", 1000000, 128000, true, "low,medium,high,xhigh,max");

        // —— Raccoon（商汤小浣熊）：推理只有开/关两档 ——
        m("sn-sensenova-6-8-flash", "SenseNova 6.8 Flash", 256000, 63999, true, "on,off");
        m("sn-sensenova-6-8-flash-lite", "SenseNova 6.8 Flash Lite", 256000, 63999, true, "on,off");
        m("sn-glm-5-3", "GLM-5.3（Raccoon）", 1000000, 100000, true, "on,off");
        m("sn-glm-5-3-flash", "GLM-5.3 Flash（Raccoon）", 1000000, 100000, true, "on,off");
        m("sn-kimi-k3", "Kimi K3（Raccoon）", 1000000, 100000, true, "on,off");
        m("sn-deepseek-v4-1-flash", "DeepSeek V4.1 Flash（Raccoon）", 1000000, 100000, true, "on,off");

        // —— ZCode（智谱）——
        m("GLM-5.3", "GLM-5.3（ZCode）", 1000000, 128000, false, "low,high,max");
        m("GLM-5.3-Flash", "GLM-5.3 Flash（ZCode）", 1000000, 128000, true, "low,high,max");

        // —— Qoder（国际版 + 中国版）——
        m("ultimate", "Ultimate", 1000000, 32768, true, "low,medium,high,xhigh,max");
        m("performance", "Performance", 1000000, 32768, true, "low,medium,high,xhigh,max");
        m("efficient", "Efficient", 200000, 32768, true, null);
        m("smodel", "Sonus", 180000, 32768, true, "low,medium,high,xhigh,max");
        m("cmodel", "Cantus", 180000, 32768, true, "low,medium,high,xhigh,max");
        m("qmodel_38max", "Qwen3.8-Max", 180000, 32768, true, "low,medium,xhigh");
        m("qfmodel", "Qwen3.8-Flash", 180000, 32768, true, "low,medium,xhigh");
        m("qmodel_latest", "Qwen3.7-Max", 180000, 32768, true, null);
        m("qmodel", "Qwen3.7-Plus", 180000, 32768, true, null);
    }

    private ModelMeta() {
    }

    /**
     * 查一个模型 id 的元信息。
     *
     * @param modelId 上游模型 id
     * @return 元信息；查不到返回 {@code null}（调用方应原样显示 id）
     */
    public static Info lookup(String modelId) {
        if (modelId == null) return null;
        Info direct = TABLE.get(modelId);
        if (direct != null) return direct;
        // 大小写不敏感的兜底：上游偶有 GLM-5.3 / glm-5.3 混用
        Info ci = TABLE.get(modelId.toLowerCase());
        if (ci != null) return ci;
        for (Map.Entry<String, Info> e : TABLE.entrySet()) {
            if (e.getKey().equalsIgnoreCase(modelId)) return e.getValue();
        }
        return null;
    }

    /**
     * 展示用名字：查得到就用友好名，查不到原样返回 id。
     *
     * @param modelId 上游模型 id
     * @return 展示名
     */
    public static String displayName(String modelId) {
        Info info = lookup(modelId);
        if (info == null) return modelId;
        // 名字与 id 实质相同时不重复显示
        if (info.name.equalsIgnoreCase(modelId)) return modelId;
        return info.name;
    }

    /**
     * 展示用规格行：查不到返回空串（调用方据此隐藏第二行）。
     *
     * @param modelId 上游模型 id
     * @return 规格描述
     */
    public static String specLine(String modelId) {
        Info info = lookup(modelId);
        return info == null ? "" : info.specLine();
    }

    /** 已知模型总数（调试 / 统计用）。 */
    public static int knownCount() {
        return TABLE.size();
    }

    /** 全部已知 id（按字母序），供测试断言。 */
    public static Map<String, String> allNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : new java.util.TreeSet<>(TABLE.keySet())) {
            out.put(id, TABLE.get(id).name);
        }
        return out;
    }
}
