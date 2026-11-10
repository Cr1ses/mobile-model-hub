package com.wbgateway;

/**
 * 验证桥接：暴露模型目录解析，供离线断言。
 *
 * <p>为什么需要：`parseCommerceModels` 是包内可见的纯函数，但「国际版 500 后要能
 * 退回 /v3/config」这个判定只在真实响应上才暴露 —— 所以既要离线断言解析，
 * 也要能用真实响应回放。
 */
public final class CatalogProbe {

    private CatalogProbe() {
    }

    /** 见 {@link LoginProviders#parseCommerceModels(org.json.JSONObject)}。 */
    public static LoginProviders.Catalog parse(org.json.JSONObject body) {
        return LoginProviders.parseCommerceModels(body);
    }

    /** 真去拉一次（会出网；仅手工排查用）。 */
    public static LoginProviders.Catalog fetch(Channel channel) throws java.io.IOException {
        return LoginProviders.fetchModelsDetailed(channel);
    }

    /** 见 {@link LoginProviders#parseRaccoonCatalog(org.json.JSONObject)}。 */
    public static LoginProviders.Catalog parseRaccoon(org.json.JSONObject body) {
        return LoginProviders.parseRaccoonCatalog(body);
    }
}
