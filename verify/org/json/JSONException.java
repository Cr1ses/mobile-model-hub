package org.json;

/**
 * 桌面验证用的最小 {@link JSONException}（见 {@link JSONObject} 的说明）。
 */
public class JSONException extends Exception {

    private static final long serialVersionUID = 1L;

    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}
