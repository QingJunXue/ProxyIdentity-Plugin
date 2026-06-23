package io.github.qingjunxue.proxyidentity;

import io.github.qingjunxue.proxyidentity.security.TrustedProxyGate;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;

public final class TelemetryCharts {
    public static String KEY_WHITELIST_COUNT = "whitelist_count";
    public static String KEY_PROTOCOLLIB_VERSION = "protocollib_version";

    public static CustomChart createWhitelistCountChart() {
        return new SimplePie(KEY_WHITELIST_COUNT, TelemetryCharts::getWhitelistCountLabel);
    }

    static String getWhitelistCountLabel() {
        return TrustedProxyGate.whitelist == null
                ? "disabled"
                : Integer.toString(TrustedProxyGate.whitelist.size());
    }

    private TelemetryCharts() {throw new AssertionError();}
}
