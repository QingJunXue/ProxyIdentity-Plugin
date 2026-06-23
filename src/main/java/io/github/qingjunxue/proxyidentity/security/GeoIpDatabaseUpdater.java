package io.github.qingjunxue.proxyidentity.security;

import com.maxmind.geoip2.DatabaseReader;
import io.github.qingjunxue.proxyidentity.util.PluginLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GeoIP 数据库异步同步器。下载源固定在代码中，不写入配置文件。
 */
public final class GeoIpDatabaseUpdater {
    private static final String DATABASE_NAME = "GeoLite2-Country.mmdb";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/QingJunXue/GeoLite.mmdb/download/";
    private static final String CDN_BASE = "https://cdn.jsdelivr.net/gh/QingJunXue/GeoLite.mmdb@download/";

    private static final String[] DATABASE_URLS = new String[] {
            RAW_BASE + DATABASE_NAME,
            CDN_BASE + DATABASE_NAME
    };

    private static final String[] CHECKSUM_URLS = new String[] {
            RAW_BASE + "SHA256SUMS",
            CDN_BASE + "SHA256SUMS"
    };

    private GeoIpDatabaseUpdater() {}

    public static boolean needsUpdate(Path database, int updateDays) {
        try {
            if (!Files.exists(database)) {
                return true;
            }
            Instant modified = Files.getLastModifiedTime(database).toInstant();
            return Duration.between(modified, Instant.now()).toDays() >= updateDays;
        } catch (IOException e) {
            return true;
        }
    }

    public static boolean update(Path database, int connectTimeoutMs, int readTimeoutMs, Logger logger) {
        Path temp = null;
        try {
            Files.createDirectories(database.getParent());
            temp = Files.createTempFile(database.getParent(), "GeoLite2-Country", ".mmdb.tmp");
            String expectedSha256 = downloadChecksum(connectTimeoutMs, readTimeoutMs, logger);
            IOException lastError = null;
            for (String url : DATABASE_URLS) {
                try {
                    PluginLogger.info(logger, "正在同步 GeoIP 数据库。");
                    download(url, temp, connectTimeoutMs, readTimeoutMs);
                    verifyDatabase(temp);
                    if (expectedSha256 != null) {
                        verifySha256(temp, expectedSha256);
                    }
                    Files.copy(temp, database, StandardCopyOption.REPLACE_EXISTING);
                    PluginLogger.info(logger, "GeoIP 数据库同步完成: " + database);
                    return true;
                } catch (IOException e) {
                    lastError = e;
                    PluginLogger.jul(logger, Level.WARNING, "当前 GeoIP 数据库同步源失败，正在尝试下一个源", e);
                }
            }
            if (lastError != null) {
                PluginLogger.jul(logger, Level.WARNING, "所有 GeoIP 数据库同步源均失败，将继续使用本地旧数据库", lastError);
            }
            return false;
        } catch (IOException e) {
            PluginLogger.jul(logger, Level.WARNING, "准备 GeoIP 数据库同步失败，将继续使用本地旧数据库", e);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String downloadChecksum(int connectTimeoutMs, int readTimeoutMs, Logger logger) {
        for (String url : CHECKSUM_URLS) {
            try {
                Path temp = Files.createTempFile("ProxyIdentity-GeoIP-SHA256SUMS", ".tmp");
                try {
                    download(url, temp, connectTimeoutMs, readTimeoutMs);
                    String content = new String(Files.readAllBytes(temp), StandardCharsets.UTF_8);
                    return parseChecksum(content);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                PluginLogger.jul(logger, Level.FINE, "当前 GeoIP 校验文件同步源失败，正在尝试下一个源", e);
            }
        }
        PluginLogger.warning(logger, "未能获取 GeoIP 数据库校验文件，将仅校验数据库格式。");
        return null;
    }

    private static String parseChecksum(String content) throws IOException {
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.endsWith(DATABASE_NAME)) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length > 0 && parts[0].matches("[0-9a-fA-F]{64}")) {
                    return parts[0].toLowerCase(Locale.ROOT);
                }
            }
        }
        throw new IOException("校验文件中未找到 " + DATABASE_NAME + " 的 SHA256");
    }

    private static void download(String url, Path destination, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载失败，HTTP 状态码: " + code);
        }
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private static void verifyDatabase(Path database) throws IOException {
        try (DatabaseReader ignored = new DatabaseReader.Builder(new File(database.toString())).build()) {
            // 成功打开即可证明文件是可读取的 MMDB 数据库。
        }
    }

    private static void verifySha256(Path file, String expected) throws IOException {
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("GeoIP 数据库 SHA256 校验失败，期望 " + expected + "，实际 " + actual);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 环境不支持 SHA-256", e);
        }
    }
}

