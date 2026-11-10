package com.wbgateway;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询本机的局域网地址，供「其他设备接入」使用。
 *
 * <h3>为什么需要单独一个类</h3>
 *
 * <p>用户要把手机上的网关接到另一台设备（平板 / 电脑 / 另一部手机）时，
 * 必须知道「填什么地址」。之前界面只显示 `127.0.0.1`，而那是**本机回环**，
 * 别的设备填了必然连不上——但界面没给出任何提示，用户只会以为功能坏了。
 *
 * <h3>为什么不能只取第一个网卡</h3>
 *
 * <p>手机同时可能有 Wi-Fi、蜂窝、USB 共享（rndis）、VPN、虚拟网卡。
 * 随便取一个很可能是蜂窝地址（对端连不上）或 VPN 地址（同样连不上）。
 * 所以这里做优先级排序：**Wi-Fi / 以太网优先，蜂窝最后**，并返回全部候选
 * 让用户自己挑（部分网络环境只有 USB 共享可用）。
 *
 * <p>实测本机（2026-11-10）：只有 `rndis0 10.101.214.52`（USB 共享）和
 * `rmnet_data1 100.93.12.191`（蜂窝），没有 Wi-Fi —— 所以「只显示一个」
 * 在这种环境下一定是错的。
 */
final class LanAddress {

    private LanAddress() {}

    /**
     * 判断是否明显属于虚拟 / 隧道网段（别的设备连不上）。
     *
     * <p>实测本机（2026-11-10）出现过 `ethernet_32770 26.141.229.248`
     * ——那是 VPN/云手机的虚拟网卡，被系统命名成 ethernet，从名字看不出。
     * 把它显示给用户当「局域网地址」只会让人白试。
     *
     * <p>规则保守：只排除**确定**不可达的段，宁可多显示一个也不误杀真实地址。
     */
    static boolean isVirtualIp(String ip) {
        if (ip == null) return true;
        // CGNAT / 运营商内网：别的设备无法直接访问
        if (ip.startsWith("100.")) {
            // 100.64.0.0/10 是 CGNAT；100.x 其他段不做判断
            String[] p = ip.split("\\.");
            if (p.length == 4) {
                try {
                    int second = Integer.parseInt(p[1]);
                    if (second >= 64 && second <= 127) return true;
                } catch (Exception ignored) {
                }
            }
        }
        // Radmin VPN / Hamachi 等虚拟组网
        if (ip.startsWith("26.")) return true;
        if (ip.startsWith("25.")) return true;
        // Docker / 虚拟机的常见段
        if (ip.startsWith("172.17.") || ip.startsWith("172.18.")
                || ip.startsWith("172.19.")) return true;
        return false;
    }

    /** 一个候选地址。 */
    static final class Candidate {
        /** 网卡名，如 `wlan0` / `rndis0`。 */
        final String iface;
        /** IPv4 地址，如 `192.168.1.5`。 */
        final String ip;
        /** 是否推荐优先使用（Wi-Fi / 以太网）。 */
        final boolean preferred;

        Candidate(String iface, String ip, boolean preferred) {
            this.iface = iface;
            this.ip = ip;
            this.preferred = preferred;
        }
    }

    /**
     * 列出所有可用的 IPv4 局域网地址，按「适合被其他设备访问」排序。
     *
     * @return 候选列表；空表示当前没有可用的局域网地址（未连 Wi-Fi / 未开共享）
     */
    static List<Candidate> list() {
        List<Candidate> wifi = new ArrayList<>();
        List<Candidate> other = new ArrayList<>();
        try {
            List<NetworkInterface> nics = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nic : nics) {
                if (nic == null || !nic.isUp() || nic.isLoopback()) continue;
                String name = nic.getName() == null ? "" : nic.getName().toLowerCase(java.util.Locale.ROOT);
                // 虚拟网卡对别的设备没有意义，直接排除
                if (name.startsWith("lo") || name.startsWith("dummy")
                        || name.startsWith("tun") || name.startsWith("tap")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;
                    // 排除明显是虚拟 / 隧道 / 云手机的网段：
                    // 这些地址别的设备连不上，显示出来只会误导用户。
                    if (isVirtualIp(ip)) continue;
                    boolean preferred = name.startsWith("wlan")
                            || name.startsWith("eth")
                            || name.startsWith("ap")
                            || name.startsWith("rndis");
                    Candidate c = new Candidate(name, ip, preferred);
                    if (preferred) wifi.add(c);
                    else other.add(c);
                }
            }
        } catch (Exception ignored) {
        }
        List<Candidate> out = new ArrayList<>();
        out.addAll(wifi);
        out.addAll(other);
        return out;
    }

    /**
     * 取推荐地址（给界面做默认值）。
     *
     * @return IP 字符串；没有可用地址时返回空串
     */
    static String best() {
        List<Candidate> all = list();
        return all.isEmpty() ? "" : all.get(0).ip;
    }

    /**
     * 拼接给其他设备用的 Base URL。
     *
     * @return 形如 `http://192.168.1.5:8787/v1`；无地址时返回空串
     */
    static String baseUrl(int port) {
        String ip = best();
        return ip.isEmpty() ? "" : "http://" + ip + ":" + port + "/v1";
    }

    /**
     * 这个网卡名是否「大概能被同一局域网内的设备访问」。
     *
     * <p>蜂窝（rmnet / ccmni 等）通常有运营商 NAT，别的设备连不上，
     * 界面要提示用户别拿它当局域网地址。
     */
    static boolean looksReachable(String iface) {
        if (iface == null) return false;
        String n = iface.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("wlan") || n.startsWith("eth")
                || n.startsWith("rndis") || n.startsWith("ap");
    }
}
