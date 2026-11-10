package com.wbgateway;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 后台保活的各项系统设置引导。
 *
 * <h3>为什么需要这个类</h3>
 *
 * <p>前台服务能挡住「普通后台回收」，但挡不住下面三类，而这恰恰是国产 ROM 上
 * 网关被杀的主因（实测本机：网关反复在几分钟内消失）：
 * <ol>
 *   <li><b>电池优化 / Doze</b> —— 息屏后系统进入 Doze，网络与闹钟被冻结，
 *       网关虽然进程还在但收不到请求；</li>
 *   <li><b>厂商「自启动 / 后台管理」</b> —— 小米、华为、OPPO、vivo 等会
 *       在息屏或划掉任务后清理后台，这一层是**系统策略**，App 自己改不了，
 *       只能引导用户去手动加白名单；</li>
 *   <li><b>CPU 休眠</b> —— 息屏后 CPU 进入深度休眠，TCP 连接得不到调度。</li>
 * </ol>
 *
 * <p>本类的职责是**准确判断状态 + 精准跳到对应设置页**，而不是假装
 * 「已经保活」——后者会让用户以为不会再被杀，实际几分钟后又断。
 *
 * <p>⚠ 不同 ROM 的设置页类名差异极大，且无法穷举。这里对主流厂商做定向尝试，
 * 失败时一律回落到「应用详情页」——那个页面每个 Android 都有，用户能自己在
 * 里面找到「自启动 / 省电策略」。
 */
final class KeepAlive {

    private KeepAlive() {}

    /** 是否已在电池优化白名单里（true = 不受 Doze 限制）。 */
    static boolean isIgnoringBatteryOptimizations(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** 是否持有「显示在其他应用上层」权限（部分 ROM 上它会影响后台存活）。 */
    static boolean canDrawOverlays(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    /**
     * 申请忽略电池优化。
     *
     * <p>Android 6+ 的正确做法是发 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * 让系统弹窗问用户；但它需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，
     * 且部分 ROM 会直接忽略。所以这里两条路都试：
     * 先发弹窗 Intent，失败再跳「电池优化」列表页。
     *
     * @return 是否成功把用户带到某个系统页面
     */
    static boolean requestIgnoreBatteryOptimizations(Activity act) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            act.startActivity(i);
            return true;
        } catch (Exception ignored) {
        }
        return openAppDetails(act);
    }

    /**
     * 跳到厂商的「自启动 / 后台管理」页面。
     *
     * <p>这些页面没有公开 API，全是各家私有 ComponentName。按市场占有率
     * 从高到低尝试，全部失败就回落到应用详情页 —— **不要静默失败**，
     * 否则用户点了按钮什么都没发生，会以为功能坏了。
     *
     * @return 是否成功跳转
     */
    static boolean openAutoStartSettings(Activity act) {
        String brand = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        String manufacturer = (Build.MANUFACTURER == null ? ""
                : Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        String key = brand + " " + manufacturer;

        String[][] candidates;
        if (key.contains("xiaomi") || key.contains("redmi") || key.contains("poco")) {
            candidates = new String[][]{
                    {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                    {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
            };
        } else if (key.contains("huawei") || key.contains("honor")) {
            candidates = new String[][]{
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            };
        } else if (key.contains("oppo") || key.contains("realme") || key.contains("oneplus")) {
            candidates = new String[][]{
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                    {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                    {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
            };
        } else if (key.contains("vivo") || key.contains("iqoo")) {
            candidates = new String[][]{
                    {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                    {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            };
        } else if (key.contains("samsung")) {
            candidates = new String[][]{
                    {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                    {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            };
        } else if (key.contains("meizu")) {
            candidates = new String[][]{
                    {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                    {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
            };
        } else if (key.contains("lenovo") || key.contains("zuk") || key.contains("motorola")) {
            candidates = new String[][]{
                    {"com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"},
                    {"com.zui.safecenter", "com.lenovo.security.purebackground.PureBackgroundActivity"},
            };
        } else if (key.contains("zte") || key.contains("nubia")) {
            candidates = new String[][]{
                    {"cn.nubia.securitymanager", "cn.nubia.securitymanager.ui.RunningAppActivity"},
                    {"com.zte.power", "com.zte.power.ui.activity.PowerActivity"},
            };
        } else if (key.contains("transsion") || key.contains("tecno")
                || key.contains("infinix") || key.contains("itel")) {
            candidates = new String[][]{
                    {"com.transsion.phonemaster", "com.transsion.phonemaster.ui.PhoneMasterActivity"},
            };
        } else if (key.contains("asus")) {
            candidates = new String[][]{
                    {"com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"},
            };
        } else if (key.contains("nokia")) {
            candidates = new String[][]{
                    {"com.evenwell.emm", "com.evenwell.emm.ui.PowerSavingActivity"},
            };
        } else {
            candidates = new String[0][];
        }

        // 品牌没命中（未知厂商 / 定制 ROM）：先按类名特征扫一遍
        if (candidates.length == 0) {
            if (openAutoStartSettingsByScan(act)) return true;
        }

        for (String[] cn : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(cn[0], cn[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (act.getPackageManager().resolveActivity(i,
                        PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    act.startActivity(i);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return openAppDetails(act);
    }

    /**
     * 通用兜底：扫描所有已安装应用，找**名字像自启管理**的 Activity。
     *
     * <p>为什么要这层：厂商页面无法穷举（新品牌、定制 ROM、运营商定制机）。
     * 与其在未知品牌上直接放弃，不如按类名特征扫一遍常见的安全中心 /
     * 手机管家，命中就跳过去。
     *
     * <p>只扫一小撮可能相关的包名（`*security*` / `*safecenter*` /
     * `*systemmanager*` / `*phonemaster*` 等），避免遍历所有应用造成卡顿。
     *
     * @return 是否成功跳转
     */
    static boolean openAutoStartSettingsByScan(Activity act) {
        String[] pkgHints = {
                "security", "safecenter", "systemmanager", "phonemaster",
                "permissionmanager", "mobilemanager", "powermanager",
        };
        String[] actHints = {
                "autostart", "startup", "bgstartup", "background", "purebackground",
                "protect", "powercenter", "powersave",
        };
        try {
            PackageManager pm = act.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (android.content.pm.ApplicationInfo app : apps) {
                String pkg = app.packageName == null ? "" : app.packageName.toLowerCase(java.util.Locale.ROOT);
                boolean pkgHit = false;
                for (String h : pkgHints) {
                    if (pkg.contains(h)) {
                        pkgHit = true;
                        break;
                    }
                }
                if (!pkgHit) continue;
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                            PackageManager.GET_ACTIVITIES);
                    if (pi.activities == null) continue;
                    for (android.content.pm.ActivityInfo ai : pi.activities) {
                        if (ai.name == null) continue;
                        String an = ai.name.toLowerCase(java.util.Locale.ROOT);
                        for (String h : actHints) {
                            if (!an.contains(h)) continue;
                            Intent i = new Intent();
                            i.setComponent(new ComponentName(pkg, ai.name));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                                act.startActivity(i);
                                return true;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 打开本应用的系统详情页（每个 Android 都有，是最终的兜底）。 */
    static boolean openAppDetails(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 跳到「最近任务」的锁定提示页（部分 ROM 支持把 App 加入白名单）。
     *
     * <p>小米/OPPO 等可以在最近任务里「锁定」App 来防止被清理，但没有
     * 标准 Intent 能直接跳过去，只能引导用户自己去锁。
     */
    static String autoStartHint() {
        String brand = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        String manufacturer = (Build.MANUFACTURER == null ? ""
                : Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        String key = brand + " " + manufacturer;

        if (key.contains("xiaomi") || key.contains("redmi") || key.contains("poco")) {
            return "小米 / Redmi：设置 → 应用设置 → 应用管理 → 本应用 → 省电策略选「无限制」，"
                    + "再进「自启动」打开；最后在最近任务里下拉本应用卡片加锁。";
        }
        if (key.contains("huawei") || key.contains("honor")) {
            return "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 本应用改为「手动管理」，"
                    + "把「自启动 / 关联启动 / 后台活动」三个都打开。";
        }
        if (key.contains("oppo") || key.contains("realme") || key.contains("oneplus")) {
            return "OPPO / 一加 / realme：设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行；"
                    + "再进「自启动管理」打开本应用。";
        }
        if (key.contains("vivo") || key.contains("iqoo")) {
            return "vivo / iQOO：设置 → 电池 → 后台高耗电 → 允许本应用；"
                    + "再进 i管家 → 应用管理 → 自启动 打开。";
        }
        if (key.contains("samsung")) {
            return "三星：设置 → 电池和设备维护 → 电池 → 后台使用限制，"
                    + "把本应用加入「永不休眠的应用」列表。";
        }
        if (key.contains("meizu")) {
            return "魅族：设置 → 电量管理 → 后台管理 → 本应用选「允许后台运行」；"
                    + "再进手机管家 → 权限管理 → 自启动 打开。";
        }
        if (key.contains("lenovo") || key.contains("zuk") || key.contains("motorola")) {
            return "联想 / Moto：设置 → 电池 → 后台耗电管理 → 本应用允许后台运行；"
                    + "再进「安全中心 → 自启动管理」打开。";
        }
        if (key.contains("zte") || key.contains("nubia")) {
            return "中兴 / 努比亚：设置 → 电池 → 应用省电策略 → 本应用选「无限制」；"
                    + "再进「手机管家 → 自启动」打开。";
        }
        if (key.contains("transsion") || key.contains("tecno")
                || key.contains("infinix") || key.contains("itel")) {
            return "传音 / TECNO：设置 → 电池 → 后台应用管理 → 本应用允许后台；"
                    + "再进 Phone Master → 自启动管理 打开。";
        }
        if (key.contains("asus")) {
            return "华硕：设置 → 电池 → 后台应用管理 → 本应用允许后台运行；"
                    + "再进 Mobile Manager → 自启动 打开。";
        }
        return "到系统设置的「应用管理」里把本应用的「自启动 / 后台运行」打开，"
                + "并把省电策略设为「无限制」。找不到的话，点上面的「去设置」按钮会尽量跳到对应页面。";
    }
}