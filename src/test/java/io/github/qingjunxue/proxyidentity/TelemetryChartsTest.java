package io.github.qingjunxue.proxyidentity;

import io.github.qingjunxue.proxyidentity.security.IpRange;
import io.github.qingjunxue.proxyidentity.security.TrustedProxyGate;
import io.github.qingjunxue.proxyidentity.security.TrustedProxyList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelemetryChartsTest {
    @AfterEach
    void resetWhitelist() {
        TrustedProxyGate.whitelist = new TrustedProxyList(Collections.emptyList());
    }

    @Test
    void whitelistCountDistinguishesDisabledWhitelist() {
        TrustedProxyGate.whitelist = null;
        assertEquals("disabled", TelemetryCharts.getWhitelistCountLabel());
    }

    @Test
    void whitelistCountReportsEntryCount() throws UnknownHostException {
        TrustedProxyGate.whitelist = new TrustedProxyList(Collections.singletonList(new IpRange("127.0.0.0", 8)));
        assertEquals("1", TelemetryCharts.getWhitelistCountLabel());
    }
}
