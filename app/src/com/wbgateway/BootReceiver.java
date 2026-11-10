package com.wbgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 开机自启 / 应用更新后自动拉起网关。
 *
 * <p>为什么需要：网关是给人「一直挂着」用的。手机重启后如果它不自己回来，
 * 用户在其他 AI App 里调用时只会看到连接失败，而且很难联想到「要去重新点启动」。
 *
 * <h3>只在用户上次是开着的状态才自启</h3>
 *
 * <p>不能无条件启动：用户主动点了「停止」之后，重启手机又被他拉起来，
 * 那是违背用户意图的。所以这里读一个偏好位 {@link #KEY_WANTED}，
 * 只有「上次退出时是运行中」才恢复。
 *
 * <p>该偏好位由 {@link GatewayService} 在启停时维护。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 用户是否希望网关常驻（由 Service 在启停时写）。 */
    static final String PREFS = "gateway_keepalive";
    static final String KEY_WANTED = "wanted";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        // 只处理这三种：开机、锁屏前启动（direct boot）、应用被覆盖安装
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_WANTED, false)) return;

        try {
            Intent svc = new Intent(context, GatewayService.class);
            svc.setAction(GatewayService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
            // 部分 ROM 在开机阶段不允许启动前台服务；用户手动打开 App 即可恢复。
            // 不在这里弹通知打扰开机流程。
        }
    }

    /** 记录「用户希望网关常驻」的意愿（Service 启停时调用）。 */
    static void setWanted(Context context, boolean wanted) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WANTED, wanted).commit();
    }
}
