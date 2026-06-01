package io.github.qingjunxue.proxyidentity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelemetryChartsTest {
    @AfterEach
    void resetWhitelist() {
        TrustedProxyList.whitelist = new TrustedProxyList(Collections.emptyList());
    }

    @Test
    void whitelistCountDistinguishesDisabledWhitelist() {
        TrustedProxyList.whitelist = null;
        assertEquals("disabled", TelemetryCharts.getWhitelistCountLabel());
    }

    @Test
    void whitelistCountReportsEntryCount() throws UnknownHostException {
        TrustedProxyList.whitelist = new TrustedProxyList(Collections.singletonList(new IpRange("127.0.0.0", 8)));
        assertEquals("1", TelemetryCharts.getWhitelistCountLabel());
    }
}
