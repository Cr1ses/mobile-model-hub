package org.json;

import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 序列化器（桌面验证用）。
 *
 * <p>只做一件事：把 {@link JSONObject} / {@link JSONArray} 内部的 Map/List 写成文本。
 * 转义规则与 `org.json` 一致（`"`、`\`、控制字符）。
 */
final class JSONWriter {

    private JSONWriter() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        // 嵌套的 JSONObject / JSONArray 必须递归下去写成对象与数组。
        // 少了这两个分支，嵌套值会被当成普通字符串写出（得到
        // `"modelSpecs":"{\"gmodel\":...}"` 这种双重编码），读回来就是 null ——
        // 这个替身差异曾让「规格存不进去」看起来像产品 bug。
        if (value instanceof JSONObject) {
            append(sb, ((JSONObject) value).toMap());
            return;
        }
        if (value instanceof JSONArray) {
            append(sb, ((JSONArray) value).toList());
            return;
        }
        if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Object entryObj : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                if (!first) sb.append(',');
                first = false;
                appendString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                append(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(',');
                first = false;
                append(sb, item);
            }
            sb.append(']');
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
            return;
        }
        appendString(sb, String.valueOf(value));
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
