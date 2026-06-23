package io.github.qingjunxue.proxyidentity.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedCountryListTest {
    @Test
    void parsesCountryCodesCaseInsensitively() {
        BlockedCountryList list = new BlockedCountryList(Arrays.asList(" cn ", "US", "  # comment"));

        assertTrue(list.matches("cn"));
        assertTrue(list.matches("Us"));
        assertFalse(list.matches("JP"));
    }

    @Test
    void ignoresEmptyEntries() {
        BlockedCountryList list = new BlockedCountryList(Collections.emptyList());

        assertFalse(list.matches("CN"));
    }
}
