package io.github.qingjunxue.proxyidentity.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.logging.Logger;
import io.github.qingjunxue.proxyidentity.util.PluginLogger;

public final class AccessControlGate {
    public static volatile IpBlockList blockedIps = new IpBlockList(java.util.Collections.<IpRange>emptyList());
    public static volatile BlockedCountryList blockedCountries = new BlockedCountryList(java.util.Collections.<String>emptyList());
    public static volatile CountryResolver countryResolver = new GeoIpCountryResolver(null);

    private AccessControlGate() {}

    public static boolean check(SocketAddress transportAddress, SocketAddress effectiveAddress) {
        InetAddress transportIp = socketAddressOf(transportAddress);
        InetAddress effectiveIp = socketAddressOf(effectiveAddress != null ? effectiveAddress : transportAddress);
        if (transportIp != null && blockedIps.matches(transportIp)) {
            return false;
        }
        if (effectiveIp != null && blockedCountries.size() > 0) {
            Optional<String> country = countryResolver.resolve(effectiveIp);
            if (country.isPresent() && blockedCountries.matches(country.get())) {
                return false;
            }
        }
        return true;
    }

    public static String warningFor(SocketAddress transportAddress, SocketAddress effectiveAddress) {
        InetAddress transportIp = socketAddressOf(transportAddress);
        InetAddress effectiveIp = socketAddressOf(effectiveAddress != null ? effectiveAddress : transportAddress);
        if (transportIp != null && blockedIps.matches(transportIp)) {
            return "连接来源地址 " + transportIp.getHostAddress() + " 命中封禁 IP 列表";
        }
        if (effectiveIp != null && blockedCountries.size() > 0) {
            Optional<String> country = countryResolver.resolve(effectiveIp);
            if (country.isPresent() && blockedCountries.matches(country.get())) {
                return "连接来源国家 " + country.get() + " 命中封禁列表";
            }
        }
        return null;
    }

    public static void logIfBlocked(Logger logger, SocketAddress transportAddress, SocketAddress effectiveAddress) {
        String warning = warningFor(transportAddress, effectiveAddress);
        if (warning != null && logger != null) {
            PluginLogger.warning(logger, warning);
        }
    }

    private static InetAddress socketAddressOf(SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getAddress();
        }
        return null;
    }
}
