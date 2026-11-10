package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析器（桌面验证用）。
 *
 * <p>只支持验证需要的那部分：对象、数组、字符串、数字、true/false/null。
 * 足够解析上游真实响应与代码自己拼的请求体；不支持尾随逗号等宽容语法，
 * 因为上游也不会发。
 */
final class JSONParser {

    private final String text;
    private int pos;

    JSONParser(String text) {
        this.text = text == null ? "" : text;
        this.pos = 0;
    }

    Object parseValue() throws JSONException {
        skipWhitespace();
        if (pos >= text.length()) throw new JSONException("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() throws JSONException {
        Map<String, Object> out = new LinkedHashMap<>();
        pos += 1; // {
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos += 1;
            return out;
        }
        for (;;) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') throw new JSONException("期望键名 @" + pos);
            String key = parseString();
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != ':') throw new JSONException("期望冒号 @" + pos);
            pos += 1;
            Object value = parseValue();
            out.put(key, value);
            skipWhitespace();
            if (pos >= text.length()) throw new JSONException("对象未闭合");
            char c = text.charAt(pos);
            if (c == ',') {
                pos += 1;
                continue;
            }
            if (c == '}') {
                pos += 1;
                return out;
            }
            throw new JSONException("对象里出现意外字符 " + c + " @" + pos);
        }
    }

    private List<Object> parseArray() throws JSONException {
        List<Object> out = new ArrayList<>();
        pos += 1; // [
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos += 1;
            return out;
        }
        for (;;) {
            out.add(parseValue());
            skipWhitespace();
            if (pos >= text.length()) throw new JSONException("数组未闭合");
            char c = text.charAt(pos);
            if (c == ',') {
                pos += 1;
                continue;
            }
            if (c == ']') {
                pos += 1;
                return out;
            }
            throw new JSONException("数组里出现意外字符 " + c + " @" + pos);
        }
    }

    private String parseString() throws JSONException {
        if (text.charAt(pos) != '"') throw new JSONException("期望引号 @" + pos);
        pos += 1;
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            pos += 1;
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) throw new JSONException("转义未完成");
            char esc = text.charAt(pos);
            pos += 1;
            switch (esc) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) throw new JSONException("\\u 转义不完整");
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new JSONException("未知转义 \\" + esc);
            }
        }
        throw new JSONException("字符串未闭合");
    }

    private Object parseNumber() throws JSONException {
        int start = pos;
        if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos += 1;
        boolean hasDot = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos += 1;
                continue;
            }
            if (c == '.' && !hasDot) {
                hasDot = true;
                pos += 1;
                continue;
            }
            if (c == 'e' || c == 'E') {
                pos += 1;
                if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos += 1;
                continue;
            }
            break;
        }
        String raw = text.substring(start, pos);
        if (raw.isEmpty()) throw new JSONException("期望数字 @" + start);
        try {
            if (hasDot) return Double.parseDouble(raw);
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return (int) value;
            return value;
        } catch (NumberFormatException e) {
            throw new JSONException("数字格式非法：" + raw);
        }
    }

    private void expect(String literal) throws JSONException {
        if (!text.startsWith(literal, pos)) throw new JSONException("期望 " + literal + " @" + pos);
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos += 1;
    }
}
