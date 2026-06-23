package io.github.qingjunxue.proxyidentity.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变的封禁 IP 范围列表。
 */
public final class IpBlockList {
    public static IpBlockList parseEntries(List<String> entries) throws java.io.IOException {
        ArrayList<IpRange> list = new ArrayList<>();
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            list.addAll(IpRange.parse(trimmed));
        }
        return new IpBlockList(list);
    }

    private final List<IpRange> list;

    public IpBlockList(List<IpRange> list) {
        this.list = Collections.unmodifiableList(new ArrayList<>(list));
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
        return list.size();
    }
}
