package io.github.qingjunxue.proxyidentity.security;

import java.net.InetAddress;
import java.util.Optional;

public interface CountryResolver {
    Optional<String> resolve(InetAddress address);
}
