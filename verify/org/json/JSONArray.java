package org.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 桌面验证用的最小 {@link JSONArray}（见 {@link JSONObject} 的说明）。
 */
public class JSONArray {

    private final List<Object> list = new ArrayList<>();

    public JSONArray() {
    }

    public JSONArray(List<Object> source) {
        list.addAll(source);
    }

    /** 暴露内部 List（给 JSONWriter 递归序列化用；Android 实现不需要这个）。 */
    List<Object> toList() {
        return list;
    }

    public JSONArray(String text) throws JSONException {
        Object parsed = new JSONParser(text).parseValue();
        if (!(parsed instanceof List)) throw new JSONException("not an array");
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) parsed;
        list.addAll(l);
    }

    /** 便捷构造：字符串数组。 */
    public JSONArray(java.util.Collection<String> values) {
        for (String v : values) list.add(v);
    }

    public JSONArray put(Object value) {
        list.add(JSONObject.wrapValue(value));
        return this;
    }

    public int length() {
        return list.size();
    }

    /**
     * 按索引取值。
     *
     * <p>⚠ 不能省略：Android 的 org.json 有 `get(int)`，调用方写
     * `arr.get(i)` 时编译会选中它，运行期按签名查找 —— 缺了就是
     * NoSuchMethodError，而单测走不到（编译期用的是真 android.jar）。
     */
    public Object get(int index) throws JSONException {
        Object v = opt(index);
        if (v == null) throw new JSONException("index " + index + " out of range");
        return v;
    }

    public Object opt(int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        if (v == null) return fallback;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        if (!(v instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return new JSONObject(m);
    }

    @Override
    public String toString() {
        return JSONWriter.write(list);
    }
}
