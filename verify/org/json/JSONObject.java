package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面验证用的 **最小** `org.json` 替身。
 *
 * <p>为什么要自己写：Android 自带 `org.json`，但桌面 JVM 没有。项目构建不引
 * Gradle/Maven，机器上也没有可用的 `json.jar`，所以为了在桌面上跑**同一份**
 * Gateway 与 ChannelLogin 代码，只能提供一个行为等价的最小实现。
 *
 * <p>⚠ 这个文件**只在 verify/ 下**，绝不参与 APK 打包（`build.ps1` 只扫
 * `app/src`）。它只为验证服务，不追求 API 完整 —— 只实现被测代码真正用到的那几个方法。
 *
 * <p>语义对齐 `org.json`（Android 版）的关键点：
 * <ul>
 *   <li>`optString(key)` 对非字符串值调用 `String.valueOf`；</li>
 *   <li>`optLong` / `optInt` 对字符串做 `parseLong` / `parseInt`，失败返回默认值；</li>
 *   <li>`has` 只判断键存在（值为 null 也算存在，与 Android 版一致）。</li>
 * </ul>
 */
public class JSONObject {

    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {
    }

    public JSONObject(String text) throws JSONException {
        Object parsed = new JSONParser(text).parseValue();
        if (!(parsed instanceof Map)) throw new JSONException("not an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        map.putAll(m);
    }

    JSONObject(Map<String, Object> source) {
        map.putAll(source);
    }

    /** 暴露内部 Map（给 JSONWriter 递归序列化用；Android 实现不需要这个）。 */
    Map<String, Object> toMap() {
        return map;
    }

    // ---- 写 ----

    public JSONObject put(String key, Object value) throws JSONException {
        // 与 Android 版对齐：嵌套对象统一存成 Map/List。真实实现里 put 一个
        // JSONObject、optJSONObject 再取回来是等价的；替身如果原样存 JSONObject，
        // optJSONObject 的 `instanceof Map` 判断就会落空 —— 这正是过去两次
        // 「桌面断言失败、产品其实没问题」的来源（见 README 的「替身差异」）。
        map.put(key, wrapValue(value));
        return this;
    }

    /** 把嵌套的 JSONObject / JSONArray 归一成 Map / List。 */
    static Object wrapValue(Object value) {
        if (value instanceof JSONObject) return ((JSONObject) value).toMap();
        if (value instanceof JSONArray) return ((JSONArray) value).toList();
        return value;
    }

    /**
     * 数值重载。
     *
     * <p>⚠ 这几个重载**不能省**：Android 的 org.json 有它们，调用方写
     * `put(key, 200)` 时编译器会选中 `(String,int)` 而不是 `(String,Object)`，
     * 于是运行期按方法签名查找 —— 缺了就是 NoSuchMethodError，
     * 而单测里根本走不到（编译期用的是真 android.jar）。
     */
    public JSONObject put(String key, int value) throws JSONException {
        return put(key, (Object) Integer.valueOf(value));
    }

    public JSONObject put(String key, long value) throws JSONException {
        return put(key, (Object) Long.valueOf(value));
    }

    public JSONObject put(String key, double value) throws JSONException {
        return put(key, (Object) Double.valueOf(value));
    }

    public JSONObject put(String key, boolean value) throws JSONException {
        map.put(key, value);
        return this;
    }

    // ---- 读 ----

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Object opt(String key) {
        return map.get(key);
    }

    public Object get(String key) throws JSONException {
        if (!map.containsKey(key)) throw new JSONException("no key " + key);
        return map.get(key);
    }

    public String optString(String key) {
        return optString(key, "");
    }

    public String optString(String key, String fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public int optInt(String key, int fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 取 double（Android 的 org.json 有这个方法，替身也要有，否则桌面编译不过）。
     *
     * <p>Qoder 目录里的 `priceFactor` 是小数（0.5 / 1.1 / 8），
     * 用 optInt 会截断成 0 或 1，所以必须用 double 版本。
     */
    public double optDouble(String key, double fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public long optLong(String key, long fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public boolean optBoolean(String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return fallback;
    }

    public JSONObject optJSONObject(String key) {
        Object v = map.get(key);
        if (!(v instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return new JSONObject(m);
    }

    public JSONArray optJSONArray(String key) {
        Object v = map.get(key);
        if (!(v instanceof List)) return null;
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) v;
        return new JSONArray(list);
    }

    public int length() {
        return map.size();
    }

    public java.util.Iterator<String> keys() {
        return new ArrayList<>(map.keySet()).iterator();
    }

    @Override
    public String toString() {
        return JSONWriter.write(map);
    }
}
