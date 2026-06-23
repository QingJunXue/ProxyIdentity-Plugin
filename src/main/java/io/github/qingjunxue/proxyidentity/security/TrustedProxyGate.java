package io.github.qingjunxue.proxyidentity.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;

/**
 * 可信代理检查的全局静态门面。
 * 维护全局白名单并提供线程安全的访问方法。
 */
public final class TrustedProxyGate {
    
    public static TrustedProxyList whitelist;
    
    private static volatile InetAddress lastWarning;
    
    private TrustedProxyGate() {
        throw new UnsupportedOperationException("工具类");
    }
    
    /**
     * 检查给定的 socket 地址是否可信。
     *
     * @param addr 要检查的 socket 地址
     * @return 如果地址在白名单中返回 true，否则返回 false
     */
    public static boolean check(SocketAddress addr) {
        if (whitelist == null) {
            return true;
        }
        if (!(addr instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress inetAddr = ((InetSocketAddress) addr).getAddress();
        return whitelist.matches(inetAddr);
    }
    
    /**
     * 为不可信地址生成警告消息（按地址限速）。
     *
     * @param socketAddress 要生成警告的 socket 地址
     * @return 可选的警告消息，如果已对该地址发出过警告则返回空
     */
    public static Optional<String> getWarningFor(SocketAddress socketAddress) {
        if (!(socketAddress instanceof InetSocketAddress)) {
            return Optional.empty();
        }
        InetAddress address = ((InetSocketAddress) socketAddress).getAddress();
        if (address.equals(lastWarning)) {
            return Optional.empty();
        }
        lastWarning = address;
        return Optional.of("代理连接来源地址 " + address + " 不在白名单中");
    }
}