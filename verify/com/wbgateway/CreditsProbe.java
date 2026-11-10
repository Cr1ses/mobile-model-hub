package com.wbgateway;

import java.util.ArrayList;
import java.util.List;

/**
 * 积分摘要 / 明细的桌面验证桥接（见 {@link Credits.Result}）。
 *
 * <p>验的是**界面文案规则**：用户明确要求只显示「多少积分」和「何时过期」，
 * 不要「N 个资源包」。这条很容易在后续改动里被加回来（因为它看起来像有用信息），
 * 所以用断言钉住。
 */
public final class CreditsProbe {

    private CreditsProbe() {
    }

    /** 构造一个结果（分项用 `名称|数量|单位|到期毫秒` 四段描述，`;` 分隔）。 */
    public static Credits.Result makeResult(double total, String unit, String packagesSpec,
                                            long nearestExpiry, String message) {
        List<Credits.Package> list = new ArrayList<>();
        if (packagesSpec != null && !packagesSpec.trim().isEmpty()) {
            for (String one : packagesSpec.split(";")) {
                String[] parts = one.split("\\|");
                if (parts.length < 2) continue;
                String name = parts[0];
                double remain = 0;
                try {
                    remain = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
                String u = parts.length > 2 ? parts[2] : "credits";
                long exp = 0;
                if (parts.length > 3) {
                    try {
                        exp = Long.parseLong(parts[3].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                list.add(new Credits.Package(name, remain, u, false, exp));
            }
        }
        return Credits.Result.demo(true, total, unit, list, nearestExpiry, message);
    }

    /** 摘要文案。 */
    public static String summary(Credits.Result r) {
        return r.summary();
    }

    /** 明细文案。 */
    public static String detail(Credits.Result r) {
        return r.detail();
    }
}
