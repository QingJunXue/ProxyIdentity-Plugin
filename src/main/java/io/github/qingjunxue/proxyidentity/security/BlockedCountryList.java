package io.github.qingjunxue.proxyidentity.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 不可变的国家码封禁列表。
 */
public final class BlockedCountryList {
    private final Set<String> countries;

    public BlockedCountryList(Collection<String> countries) {
        HashSet<String> normalized = new HashSet<>();
        if (countries != null) {
            for (String country : countries) {
                String trimmed = normalize(country);
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed);
                }
            }
        }
        this.countries = Collections.unmodifiableSet(normalized);
    }

    public boolean matches(String countryCode) {
        String normalized = normalize(countryCode);
        return !normalized.isEmpty() && countries.contains(normalized);
    }

    public int size() {
        return countries.size();
    }

    public static BlockedCountryList parseEntries(Collection<String> entries) {
        ArrayList<String> normalized = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                String trimmed = normalize(entry);
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    normalized.add(trimmed);
                }
            }
        }
        return new BlockedCountryList(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
