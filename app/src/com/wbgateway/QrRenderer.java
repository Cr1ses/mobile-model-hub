package com.wbgateway;

import android.graphics.Bitmap;

/**
 * 把 {@link QrCode} 的模块矩阵画成位图。
 *
 * <p>为什么要单独一层：编码（{@link QrCode}）与绘制分开，编码器才能在桌面上
 * 与参考实现逐位对账；绘制只关心「每模块几像素、静区留多少」。
 *
 * <p>静区（quiet zone）必须是 **4 个模块**：少了扫码枪与相机解不出，
 * 多则白白占地方。这里统一按 4 模块留白，与规范一致。
 */
public final class QrRenderer {

    /** 规范要求的静区宽度（模块数）。 */
    private static final int QUIET_ZONE = 4;

    private QrRenderer() {
    }

    /**
     * 渲染成位图。
     *
     * @param qr        编码结果
     * @param targetPx  期望边长（像素）；实际会按模块数向下取整，保证每模块等宽
     * @param light     浅色（背景）
     * @param dark      深色（模块）
     * @return 位图
     */
    public static Bitmap toBitmap(QrCode qr, int targetPx, int light, int dark) {
        int modules = qr.size() + QUIET_ZONE * 2;
        // 每模块整数像素，避免缩放导致的锯齿把模块糊在一起（扫不出来的常见原因）
        int scale = Math.max(1, targetPx / modules);
        int sizePx = modules * scale;

        int[] pixels = new int[sizePx * sizePx];
        for (int i = 0; i < pixels.length; i += 1) pixels[i] = light;

        for (int my = 0; my < qr.size(); my += 1) {
            for (int mx = 0; mx < qr.size(); mx += 1) {
                if (!qr.isDark(mx, my)) continue;
                int x0 = (mx + QUIET_ZONE) * scale;
                int y0 = (my + QUIET_ZONE) * scale;
                for (int dy = 0; dy < scale; dy += 1) {
                    int row = (y0 + dy) * sizePx + x0;
                    for (int dx = 0; dx < scale; dx += 1) pixels[row + dx] = dark;
                }
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888);
    }
}
