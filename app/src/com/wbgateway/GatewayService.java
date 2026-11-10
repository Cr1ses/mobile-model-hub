package com.wbgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：保证网关在后台常驻。
 *
 * 为什么必须前台：Android 8+ 会杀后台进程；其他 AI App 调用 127.0.0.1 时
 * 网关必须活着。前台服务 + 常驻通知是唯一合规的保活方式。
 */
public class GatewayService extends Service {

    public static final String ACTION_START = "com.wbgateway.START";
    public static final String ACTION_STOP = "com.wbgateway.STOP";
    private static final String CHANNEL_ID = "gateway";
    private static final int NOTIFICATION_ID = 1;

    /**
     * 持有 CPU 唤醒锁，防止息屏后 CPU 深度休眠导致 TCP 得不到调度。
     *
     * <p>为什么需要：前台服务只保证「进程不被回收」，不保证「CPU 不休眠」。
     * 实测息屏几分钟后请求会超时 —— 进程还在、端口也在听，但得不到调度。
     * 这把锁只阻止 CPU 休眠，**不保持屏幕常亮**（不耗电到那种程度）。
     *
     * <p>⚠ 必须成对释放：{@link #startWakeLock} / {@link #releaseWakeLock}，
     * 否则会留下「耗电异常」的口碑问题（系统也会提示本应用耗电）。
     */
    private android.os.PowerManager.WakeLock wakeLock;

    /** 全局单例：UI 与 Service 共享同一个网关实例。 */
    private static Gateway gateway;
    private static Gateway.Listener uiListener;

    public static Gateway getGateway() {
        return gateway;
    }

    public static void setUiListener(Gateway.Listener listener) {
        uiListener = listener;
        if (gateway != null && listener != null) {
            listener.onStateChanged(gateway.isRunning(), 0);
        }
    }

    /**
     * 转发给 UI 的监听器。
     *
     * 为什么用具名类而不是匿名类：build-tools 34 的 d8 在匿名内部类上会触发
     * 一个内部错误（`NullPointerException: String.length()`），导致 dex 失败。
     * 具名类走的是另一条生成路径，能稳定通过。
     */
    private static final class ForwardingListener implements Gateway.Listener {
        @Override
        public void onLog(String line) {
            Gateway.Listener target = uiListener;
            if (target != null) target.onLog(line);
        }

        @Override
        public void onStateChanged(boolean running, int port) {
            Gateway.Listener target = uiListener;
            if (target != null) target.onStateChanged(running, port);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopGateway();
            // 用户主动停止：清掉「希望常驻」，重启手机时不要自启
            BootReceiver.setWanted(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("网关运行中"));
        startWakeLock();
        // 记下「用户希望常驻」：重启手机后 BootReceiver 据此决定要不要自启。
        // 不能无条件自启 —— 用户主动停掉后又被拉起来是违背意图的。
        BootReceiver.setWanted(this, true);
        if (gateway == null) {
            Config config = new Config(this);
            gateway = new Gateway(config, new ForwardingListener());
        }
        // ⚠ 不能只看 `gateway.isRunning()`：那个布尔值只表示「start 过」，
        //   不代表 accept 线程还活着。实测（2026-11-10）出现过
        //   `isRunning()==true` + 端口在 LISTEN + 但连接一律超时 —— 线程已死。
        //   所以这里再探一次「端口是否真的能连」，健康才跳过启动。
        if (!gateway.isRunning() || !isPortHealthy()) {
            if (gateway.isRunning()) {
                Gateway.Listener target = uiListener;
                if (target != null) target.onLog("检测到网关无响应，正在重启…");
                try {
                    gateway.stop();
                } catch (Exception ignored) {
                }
            }
            try {
                gateway.start();
            } catch (Exception e) {
                Gateway.Listener target = uiListener;
                if (target != null) target.onLog("启动失败：" + e.getMessage());
            }
        }
        return START_STICKY;
    }

    /**
     * 探测网关端口是否真的能接受连接。
     *
     * <p>为什么需要：`Gateway.isRunning()` 只是个布尔标志，accept 线程意外
     * 退出后它仍然是 true，端口也还挂在 LISTEN（由内核维持），但不会有人
     * 接受连接 —— 表现就是「端口通、请求全部超时」。只看标志会永远不重启。
     *
     * @return 能连上返回 true
     */
    private boolean isPortHealthy() {
        java.net.Socket s = new java.net.Socket();
        try {
            int port = new Config(this).port();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 强制重启网关（供 UI 的「启动」按钮调用）。
     *
     * <p>为什么不能只发 START Intent：`onStartCommand` 只在服务**未运行**时
     * 才会走启动分支；服务已在运行但 accept 线程已死时，再发 Intent 也
     * 只会执行一遍「已运行就跳过」。所以这里直接停掉再起。
     *
     * @return 重启后是否健康
     */
    public static boolean forceRestart() {
        Gateway g = gateway;
        if (g == null) return false;
        try {
            g.stop();
        } catch (Exception ignored) {
        }
        try {
            g.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 端口是否真的能连上（供 UI 判断要不要重启）。 */
    public static boolean healthy(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 600);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void stopGateway() {
        if (gateway != null && gateway.isRunning()) gateway.stop();
        releaseWakeLock();
    }

    /** 拿一把只阻止 CPU 休眠的唤醒锁（不亮屏）。失败不影响主流程。 */
    private void startWakeLock() {
        if (wakeLock != null) return;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK, "modelhub:server");
            wakeLock.setReferenceCounted(false);
            // 不设超时：网关是常驻服务，超时自动释放会让息屏后又断。
            // 释放由 stopGateway / onDestroy 负责。
            wakeLock.acquire();
        } catch (Exception e) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "网关状态", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("统一 API 网关运行状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("统一 API 网关")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopGateway();
        releaseWakeLock();
        super.onDestroy();
    }

    /**
     * 系统在内存紧张时清掉服务后会回调这里。
     *
     * <p>返回 START_STICKY 表示「请尽量重启我」——但**系统不保证**：
     * 重启时 intent 为 null，且部分 ROM 直接不重启。所以真正的可靠性
     * 靠「电池优化白名单 + 厂商自启动」（见 KeepAlive），不能只靠这个返回值。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉时，尽量把服务拉回来：常驻网关被划掉就断网了。
        try {
            Intent restart = new Intent(getApplicationContext(), GatewayService.class);
            restart.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(restart);
            } else {
                getApplicationContext().startService(restart);
            }
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }
}
