package io.github.qingjunxue.proxyidentity.security;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 不可变的可信代理 IP 范围列表。
 * 用于 IP 匹配操作的线程安全数据容器。
 */
public class TrustedProxyList {

    public static TrustedProxyList parseEntries(List<String> entries) throws IOException {
        ArrayList<IpRange> list = new ArrayList<>();
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            list.addAll(IpRange.parse(trimmed));
        }
        return new TrustedProxyList(list);
    }

    private final List<IpRange> list;

    public TrustedProxyList(List<IpRange> list) {
        this.list = new ArrayList<>(list);
    }

    public boolean matches(InetAddress addr) {
        for (IpRange ip : list) {
            if (ip.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return this.list.size();
    }

    @Override
    public String toString() {
        return "TrustedProxyList" + list;
    }
}