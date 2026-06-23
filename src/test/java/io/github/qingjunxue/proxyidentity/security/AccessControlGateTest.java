package io.github.qingjunxue.proxyidentity.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessControlGateTest {
    @AfterEach
    void resetGate() {
        AccessControlGate.blockedIps = new IpBlockList(Collections.emptyList());
        AccessControlGate.blockedCountries = new BlockedCountryList(Collections.emptyList());
        AccessControlGate.countryResolver = address -> java.util.Optional.empty();
    }

    @Test
    void blocksTransportIpByCidr() throws UnknownHostException {
        AccessControlGate.blockedIps = new IpBlockList(IpRange.parse("176.65.148.0/24"));

        boolean allowed = AccessControlGate.check(
                new InetSocketAddress(InetAddress.getByName("176.65.148.136"), 25565),
                null);

        assertFalse(allowed);
    }

    @Test
    void blocksEffectiveCountryByResolver() throws UnknownHostException {
        AccessControlGate.blockedCountries = new BlockedCountryList(Collections.singleton("CN"));
        AccessControlGate.countryResolver = address -> java.util.Optional.of("CN");

        boolean allowed = AccessControlGate.check(
                new InetSocketAddress(InetAddress.getByName("203.0.113.10"), 25565),
                new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 45678));

        assertFalse(allowed);
    }

    @Test
    void allowsUnmatchedTraffic() throws UnknownHostException {
        AccessControlGate.blockedIps = new IpBlockList(IpRange.parse("192.0.2.0/24"));
        AccessControlGate.blockedCountries = new BlockedCountryList(Collections.singleton("RU"));
        AccessControlGate.countryResolver = address -> java.util.Optional.of("US");

        boolean allowed = AccessControlGate.check(
                new InetSocketAddress(InetAddress.getByName("203.0.113.10"), 25565),
                new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 45678));

        assertTrue(allowed);
    }
}
