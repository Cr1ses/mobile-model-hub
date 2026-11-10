package com.wbgateway;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 视觉样式工具：颜色、圆角卡片、按钮、字体层级。
 *
 * 为什么全部手写而不引 Material Components：这个 APK 走「SDK 工具链直编」
 * 的构建方式（不用 Gradle），任何第三方依赖都要手工凑 AAR，得不偿失。
 * 纯 framework 的 GradientDrawable 已经够做出干净的卡片式界面。
 *
 * 配色刻意与桌面版管理页（`#0d1117` 背景 / `#161b22` 卡片 / `#30363d` 描边）
 * 保持一致，两处看起来是同一个产品。
 */
public final class Ui {

    /** 页面背景。 */
    public static final int BG = 0xFF0D1117;
    /** 卡片背景。 */
    public static final int CARD = 0xFF161B22;
    /** 卡片描边。 */
    public static final int BORDER = 0xFF30363D;
    /** 主文字。 */
    public static final int TEXT = 0xFFE6EDF3;
    /** 次要文字。 */
    public static final int MUTED = 0xFF8B949E;
    /** 成功 / 运行中。 */
    public static final int GREEN = 0xFF3FB950;
    /** 主操作 / 链接。 */
    public static final int BLUE = 0xFF58A6FF;
    /** 危险 / 停止。 */
    public static final int RED = 0xFFF85149;
    /** 警告 / 待处理。 */
    public static final int YELLOW = 0xFFD29922;

    private Ui() {
    }

    /** dp → px。 */
    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 圆角矩形背景（可带描边）。 */
    public static GradientDrawable roundRect(int fill, float radiusPx, int strokeColor, float strokeWidthPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) d.setStroke((int) strokeWidthPx, strokeColor);
        return d;
    }

    /** 圆形背景（状态点用）。 */
    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 卡片容器：圆角 + 描边 + 内边距。 */
    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 14);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(roundRect(CARD, dp(c, 12), BORDER, dp(c, 1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        box.setLayoutParams(lp);
        return box;
    }

    /** 页面大标题。 */
    public static TextView pageTitle(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        v.setGravity(Gravity.START);
        return v;
    }

    /** 卡片小标题（分组名）。 */
    public static TextView sectionTitle(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setLetterSpacing(0.06f);
        v.setAllCaps(false);
        v.setPadding(dp(c, 2), dp(c, 10), 0, dp(c, 6));
        return v;
    }

    /** 正文。 */
    public static TextView body(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return v;
    }

    /** 次要说明。 */
    public static TextView caption(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setLineSpacing(dp(c, 2), 1f);
        return v;
    }

    /** 等宽文本（URL / Key 等要逐字看清的内容）。 */
    public static TextView mono(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        return v;
    }

    /**
     * 扁圆角按钮。
     *
     * @param color 底色；传 0 表示描边按钮（透明底 + 描边）
     */
    public static Button button(Context c, String text, int color) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        b.setStateListAnimator(null);
        if (color == 0) {
            b.setTextColor(TEXT);
            b.setBackground(roundRect(0x00000000, dp(c, 8), BORDER, dp(c, 1)));
        } else {
            b.setTextColor(0xFF0D1117);
            b.setBackground(roundRect(color, dp(c, 8), color, dp(c, 1)));
        }
        return b;
    }

    /** 小号文字按钮（行内操作，如「复制」「管理」）。 */
    public static Button linkButton(Context c, String text, int color) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(color);
        b.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        b.setMinHeight(dp(c, 30));
        b.setMinimumHeight(dp(c, 30));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(roundRect(0x00000000, dp(c, 6), BORDER, dp(c, 1)));
        return b;
    }

    /** 状态点（8dp 圆点）。 */
    public static View dot(Context c, int color) {
        View v = new View(c);
        int size = dp(c, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(c, 6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        v.setBackground(circle(color));
        return v;
    }

    /** 小徽标（胶囊），如「运行中」「国内版」。 */
    public static TextView pill(Context c, String text, int color) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        v.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        // 半透明底 + 同色描边
        int fill = (color & 0x00FFFFFF) | 0x22000000;
        v.setBackground(roundRect(fill, dp(c, 20), color, dp(c, 1)));
        return v;
    }

    /** 水平分隔线。 */
    public static View divider(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1));
        lp.topMargin = dp(c, 10);
        lp.bottomMargin = dp(c, 10);
        v.setLayoutParams(lp);
        v.setBackgroundColor(BORDER);
        return v;
    }

    /** 水平排列容器（带权重的主内容 + 右侧操作）。 */
    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** 占满剩余宽度的布局参数。 */
    public static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
