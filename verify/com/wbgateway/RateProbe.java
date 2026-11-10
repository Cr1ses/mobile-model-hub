package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 验证桥接：暴露「积分倍率」相关的包内函数。
 *
 * <p>这三条都是**最容易悄悄错掉**的判定：
 * <ul>
 *   <li>倍率归一化：三个上游三种写法（`x0.69` / `0.7x` / 数字），错了界面会显示
 *       「x0.7x」这种畸形串，看着像 bug；</li>
 *   <li>CodeArts 分档计费：只取第一档，但必须带备注，否则用户会以为高价档也是低价；</li>
 *   <li>促销时间窗：过期的促销若照显示，用户会以为贵模型现在免费，真调用就扣积分。</li>
 * </ul>
 */
public final class RateProbe {

    private RateProbe() {
    }

    /** 见 {@link LoginProviders#applyCodeArtsCredit(ModelSpec, JSONArray)}。 */
    public static void applyCodeArtsCredit(ModelSpec spec, JSONArray credit) {
        LoginProviders.applyCodeArtsCredit(spec, credit);
    }

    /** 见 {@link LoginProviders#promotionActiveNow(JSONObject, long)}。 */
    public static boolean promotionActiveNow(JSONObject promo, long nowMs) {
        return LoginProviders.promotionActiveNow(promo, nowMs);
    }

    /** 见 {@link LoginProviders#applyPromotions(JSONArray, LoginProviders.Catalog)}。 */
    public static void applyPromotions(JSONArray promotions, LoginProviders.Catalog catalog) {
        LoginProviders.applyPromotions(promotions, catalog);
    }

    /**
     * 见 {@link MainActivity#creditRateText(ModelSpec)}（界面真正显示的文案）。
     *
     * <p>⚠ 不能直接调 `MainActivity.creditRateText`：它是 Activity 子类的静态方法，
     * 桌面加载会 `NoClassDefFoundError: android/app/Activity`。所以这里复刻它的
     * 几行实现（有分档备注才用长文案），并断言它与 UI 走同一份
     * {@link ModelSpec#creditRateBadge()} / {@link ModelSpec#creditRateLabel()} ——
     * UI 那边只是这一层委托，逻辑只有一处。
     */
    public static String creditRateText(ModelSpec upstream) {
        if (upstream == null) return "";
        if (!upstream.creditNote.isEmpty()) return upstream.creditRateLabel();
        return upstream.creditRateBadge();
    }
}
