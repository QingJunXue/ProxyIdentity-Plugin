package io.github.qingjunxue.proxyidentity;

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

class GuardConfigTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetGuardConfig() {
        GuardConfig.debug = false;
        GuardConfig.proxyProtocolEnabled = true;
        GuardConfig.acceptV1 = true;
        GuardConfig.acceptV2 = true;
    }

    @Test
    void createsDefaultGuardConfigWithDebugDisabled() throws IOException {
        GuardConfig.debug = true;

        GuardConfig.loadOrDefault(tempDir);

        assertTrue(Files.exists(tempDir.resolve("config.yml")));
        assertFalse(GuardConfig.debug);
        assertTrue(GuardConfig.proxyProtocolEnabled);
        assertTrue(GuardConfig.acceptV1);
        assertTrue(GuardConfig.acceptV2);
        assertEquals(5, GuardConfig.trustedProxies.size());
    }

    @Test
    void enablesDebugFromTruthyValues() throws IOException {
        Files.write(tempDir.resolve("config.yml"), "debug: 1\n".getBytes(StandardCharsets.UTF_8));

        GuardConfig.loadOrDefault(tempDir);

        assertTrue(GuardConfig.debug);
    }

    @Test
    void reloadResetsMissingDebugToDefault() throws IOException {
        Files.write(tempDir.resolve("config.yml"), "debug: true\n".getBytes(StandardCharsets.UTF_8));
        GuardConfig.loadOrDefault(tempDir);
        assertTrue(GuardConfig.debug);

        Files.write(tempDir.resolve("config.yml"), "# debug omitted\n".getBytes(StandardCharsets.UTF_8));
        GuardConfig.loadOrDefault(tempDir);

        assertFalse(GuardConfig.debug);
    }

    @Test
    void parsesQuotedValuesAndIgnoresTrailingComments() throws IOException {
        Files.write(tempDir.resolve("config.yml"),
                "debug: \"on\" # enable extra logs\n".getBytes(StandardCharsets.UTF_8));

        GuardConfig.loadOrDefault(tempDir);

        assertTrue(GuardConfig.debug);
    }

    @Test
    void parsesUnifiedProxyProtocolConfig() throws IOException {
        Files.write(tempDir.resolve("config.yml"), String.join("\n",
                "proxy-protocol:",
                "  enabled: false",
                "  accept-v1: false",
                "  accept-v2: true",
                "security:",
                "  trusted-proxies:",
                "    - 127.0.0.1",
                "    - 192.168.0.0/16",
                "debug: yes",
                "").getBytes(StandardCharsets.UTF_8));

        GuardConfig.loadOrDefault(tempDir);

        assertFalse(GuardConfig.proxyProtocolEnabled);
        assertFalse(GuardConfig.acceptV1);
        assertTrue(GuardConfig.acceptV2);
        assertTrue(GuardConfig.debug);
        assertEquals(2, GuardConfig.trustedProxies.size());
    }
}
