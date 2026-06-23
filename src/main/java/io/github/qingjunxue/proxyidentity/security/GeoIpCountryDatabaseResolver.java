package io.github.qingjunxue.proxyidentity.security;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Optional;

public final class GeoIpCountryDatabaseResolver implements CountryResolver {
    private final DatabaseReader databaseReader;

    public GeoIpCountryDatabaseResolver(Path databasePath) throws IOException {
        this.databaseReader = new DatabaseReader.Builder(new File(databasePath.toString())).build();
    }

    @Override
    public Optional<String> resolve(InetAddress address) {
        try {
            CountryResponse response = databaseReader.country(address);
            if (response == null || response.getCountry() == null) {
                return Optional.empty();
            }
            String code = response.getCountry().getIsoCode();
            if (code == null || code.trim().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(code.trim().toUpperCase());
        } catch (AddressNotFoundException e) {
            return Optional.empty();
        } catch (GeoIp2Exception e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
