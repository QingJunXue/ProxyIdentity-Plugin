package io.github.qingjunxue.proxyidentity;

import io.github.qingjunxue.proxyidentity.security.TrustedProxyList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyIdentityConfigTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetProxyIdentityConfig() {
        ProxyIdentityConfig.configVersion = 3;
        ProxyIdentityConfig.debug = false;
        ProxyIdentityConfig.proxyProtocolEnabled = true;
        ProxyIdentityConfig.requireProxyHeader = true;
        ProxyIdentityConfig.acceptV1 = true;
        ProxyIdentityConfig.acceptV2 = true;
        ProxyIdentityConfig.geoIpEnabled = false;
        ProxyIdentityConfig.geoIpAutoUpdate = false;
    }

    @Test
    void createsDefaultProxyIdentityConfigWithDebugDisabled() throws IOException {
        ProxyIdentityConfig.debug = true;

        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertTrue(Files.exists(tempDir.resolve("config.yml")));
        assertFalse(ProxyIdentityConfig.debug);
        assertEquals(3, ProxyIdentityConfig.configVersion);
        assertTrue(ProxyIdentityConfig.proxyProtocolEnabled);
        assertTrue(ProxyIdentityConfig.requireProxyHeader);
        assertTrue(ProxyIdentityConfig.acceptV1);
        assertTrue(ProxyIdentityConfig.acceptV2);
        assertEquals(5, ProxyIdentityConfig.trustedProxies.size());
    }

    @Test
    void writesDebugImmediatelyAfterConfigVersionInDefaultConfig() throws IOException {
        ProxyIdentityConfig.loadOrDefault(tempDir);

        String config = new String(Files.readAllBytes(tempDir.resolve("config.yml")), StandardCharsets.UTF_8);
        int versionIndex = config.indexOf("config-version: 3");
        int debugIndex = config.indexOf("debug: false");
        int proxyProtocolIndex = config.indexOf("proxy-protocol:");

        assertTrue(versionIndex >= 0);
        assertTrue(debugIndex > versionIndex);
        assertTrue(proxyProtocolIndex > debugIndex);
        assertFalse(config.contains("raw.githubusercontent.com"));
        assertFalse(config.contains("cdn.jsdelivr.net"));
    }

    @Test
    void enablesDebugFromTruthyValues() throws IOException {
        Files.write(tempDir.resolve("config.yml"), "debug: 1\n".getBytes(StandardCharsets.UTF_8));

        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertTrue(ProxyIdentityConfig.debug);
    }

    @Test
    void reloadResetsMissingDebugToDefault() throws IOException {
        Files.write(tempDir.resolve("config.yml"), "debug: true\n".getBytes(StandardCharsets.UTF_8));
        ProxyIdentityConfig.loadOrDefault(tempDir);
        assertTrue(ProxyIdentityConfig.debug);

        Files.write(tempDir.resolve("config.yml"), "# debug omitted\n".getBytes(StandardCharsets.UTF_8));
        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertFalse(ProxyIdentityConfig.debug);
    }

    @Test
    void parsesQuotedValuesAndIgnoresTrailingComments() throws IOException {
        Files.write(tempDir.resolve("config.yml"),
                "debug: \"on\" # enable extra logs\n".getBytes(StandardCharsets.UTF_8));

        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertTrue(ProxyIdentityConfig.debug);
    }

    @Test
    void parsesUnifiedProxyProtocolConfig() throws IOException {
        Files.write(tempDir.resolve("config.yml"), String.join("\n",
                "config-version: 2",
                "proxy-protocol:",
                "  enabled: false",
                "  require-header: false",
                "  accept-v1: false",
                "  accept-v2: true",
                "security:",
                "  trusted-proxies:",
                "    - 127.0.0.1",
                "    - 192.168.0.0/16",
                "debug: yes",
                "").getBytes(StandardCharsets.UTF_8));

        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertFalse(ProxyIdentityConfig.proxyProtocolEnabled);
        assertFalse(ProxyIdentityConfig.requireProxyHeader);
        assertFalse(ProxyIdentityConfig.acceptV1);
        assertTrue(ProxyIdentityConfig.acceptV2);
        assertTrue(ProxyIdentityConfig.debug);
        assertEquals(2, ProxyIdentityConfig.trustedProxies.size());
    }

    @Test
    void upgradesOldConfigWithRequiredProxyHeader() throws IOException {
        Path config = tempDir.resolve("config.yml");
        Files.write(config, String.join("\n",
                "proxy-protocol:",
                "  enabled: true",
                "  accept-v1: true",
                "  accept-v2: true",
                "security:",
                "  trusted-proxies:",
                "    - 127.0.0.1",
                "debug: false",
                "").getBytes(StandardCharsets.UTF_8));

        ProxyIdentityConfig.loadOrDefault(tempDir);

        String upgraded = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        assertEquals(3, ProxyIdentityConfig.configVersion);
        assertTrue(ProxyIdentityConfig.requireProxyHeader);
        assertTrue(upgraded.contains("config-version: 3"));
        assertTrue(upgraded.contains("require-header: true"));
    }

    @Test
    void parsesBlockedIpsAndCountriesAndGeoIpSettings() throws IOException {
        Files.write(tempDir.resolve("config.yml"), String.join("\n",
                "config-version: 3",
                "security:",
                "  blocked-ips:",
                "    - 176.65.148.0/24",
                "    - 192.0.2.8",
                "  blocked-countries:",
                "    - cn",
                "    - RU",
                "geoip:",
                "  enabled: true",
                "  database-path: GeoLite2-Country.mmdb",
                "  auto-update: true",
                "  update-days: 7",
                "  connect-timeout-ms: 5000",
                "  read-timeout-ms: 15000",
                "").getBytes(StandardCharsets.UTF_8));

        ProxyIdentityConfig.loadOrDefault(tempDir);

        assertTrue(ProxyIdentityConfig.geoIpEnabled);
        assertEquals("GeoLite2-Country.mmdb", ProxyIdentityConfig.geoIpDatabasePath);
        assertTrue(ProxyIdentityConfig.geoIpAutoUpdate);
        assertEquals(7, ProxyIdentityConfig.geoIpUpdateDays);
        assertEquals(5000, ProxyIdentityConfig.geoIpConnectTimeoutMs);
        assertEquals(15000, ProxyIdentityConfig.geoIpReadTimeoutMs);
        assertTrue(ProxyIdentityConfig.blockedIps.size() >= 2);
        assertTrue(ProxyIdentityConfig.blockedCountries.matches("CN"));
        assertTrue(ProxyIdentityConfig.blockedCountries.matches("ru"));
    }
}
