package com.wbgateway;

/**
 * 渠道标题的桌面验证桥接（见 {@link Channel#displayTitle()}）。
 *
 * <p>这条逻辑是用户直接报上来的现象：界面上出现
 * 「Qoder（中国版） · Qoder（中国版）」，看着像同一渠道渲染了两遍。
 * 实际是取不到真实昵称时把渠道名当昵称存了下来，标题把它拼了两次。
 */
public final class TitleProbe {

    private TitleProbe() {
    }

    /** 按界面同样的规则拼标题。 */
    public static String channelTitle(String name, String nickname, String loginType) {
        Channel c = new Channel();
        c.name = name == null ? "" : name;
        c.nickname = nickname == null ? "" : nickname;
        c.loginType = loginType == null ? "" : loginType;
        return c.displayTitle();
    }
}
