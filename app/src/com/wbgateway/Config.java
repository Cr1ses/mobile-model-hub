package com.wbgateway;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 配置存储：API Key、端口、渠道清单。
 *
 * 用 SharedPreferences 而不是文件：Android 上它随应用卸载自动清理，
 * 且不需要处理存储权限。
 */
public class Config implements Gateway.Store {

    private static final String PREF = "modelhub";
    private static final String KEY_API = "apiKey";
    private static final String KEY_PORT = "port";
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_LAN = "lan";

    private final SharedPreferences prefs;

    public Config(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 网关自己的 API Key（客户端调用时用）；首次生成后保持不变。 */
    public String apiKey() {
        String existing = prefs.getString(KEY_API, "");
        if (existing != null && !existing.trim().isEmpty()) return existing.trim();
        String generated = "wbg-" + UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_API, generated).commit();
        return generated;
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API, key == null ? "" : key.trim()).commit();
    }

    /** 监听端口，默认 8787。 */
    public int port() {
        return prefs.getInt(KEY_PORT, 8787);
    }

    public void setPort(int port) {
        prefs.edit().putInt(KEY_PORT, port).commit();
    }

    /** 是否监听 0.0.0.0（局域网可访问）；默认 false，只听 127.0.0.1。 */
    public boolean lan() {
        return prefs.getBoolean(KEY_LAN, false);
    }

    public void setLan(boolean lan) {
        prefs.edit().putBoolean(KEY_LAN, lan).commit();
    }

    /** 读取全部渠道。 */
    public List<Channel> channels() {
        List<Channel> out = new ArrayList<>();
        String raw = prefs.getString(KEY_CHANNELS, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Channel c = Channel.fromJson(o);
                // 迁移：早期版本在取不到真实昵称时把「渠道显示名」当昵称存了下来，
                // 于是界面显示成「Qoder（中国版） · Qoder（中国版）」，看着像重复渲染。
                // 这里在读入时清掉这类无信息量的昵称（不改写存储，只影响内存表现）。
                if (c.nickname.equals(c.name) || c.nickname.equals(c.loginType)) {
                    c.nickname = "";
                }
                out.add(c);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 保存全部渠道。 */
    /**
     * 保存渠道清单。
     *
     * <p>⚠ 必须用 `commit()` 而不是 `apply()`：渠道配置（尤其模型勾选）
     * 是用户刚做的操作，`apply()` 是**异步落盘**，进程随后被系统回收
     * （或用户划掉最近任务）时写入会丢 —— 表现就是「勾选好的模型
     * 重启后全没了、于是调用报模型已关闭」。
     *
     * <p>代价是主线程上有一次磁盘写（几 KB），相对丢配置可接受。
     */
    public void saveChannels(List<Channel> channels) {
        JSONArray arr = new JSONArray();
        for (Channel c : channels) arr.put(c.toJson());
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).commit();
    }

    /** 新增或更新一个渠道（按 id 匹配）。 */
    public void upsertChannel(Channel next) {
        List<Channel> list = channels();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(next.id)) {
                list.set(i, next);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(next);
        saveChannels(list);
    }

    /** 删除一个渠道。 */
    public void removeChannel(String id) {
        List<Channel> list = channels();
        List<Channel> kept = new ArrayList<>();
        for (Channel c : list) {
            if (!c.id.equals(id)) kept.add(c);
        }
        saveChannels(kept);
    }

    /** 按 id 查找渠道。 */
    public Channel findChannel(String id) {
        for (Channel c : channels()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }
}
