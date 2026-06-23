package io.github.qingjunxue.proxyidentity.security;

import java.net.InetAddress;
import java.util.Optional;

public final class GeoIpCountryResolver implements CountryResolver {
    private final CountryResolver delegate;

    public GeoIpCountryResolver(CountryResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<String> resolve(InetAddress address) {
        return delegate == null ? Optional.<String>empty() : delegate.resolve(address);
    }
}
