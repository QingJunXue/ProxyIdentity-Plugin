package io.github.qingjunxue.proxyidentity;

import io.github.qingjunxue.proxyidentity.security.TrustedProxyGate;
import io.github.qingjunxue.proxyidentity.security.AccessControlGate;
import io.github.qingjunxue.proxyidentity.security.GeoIpDatabaseUpdater;
import io.github.qingjunxue.proxyidentity.security.GeoIpCountryDatabaseResolver;
import io.github.qingjunxue.proxyidentity.util.PluginLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台插件通用启动逻辑
 */
public final class PlatformBootstrap {
    private PlatformBootstrap() {}

    /**
     * 执行标准的插件启动流程：加载配置、设置白名单、检查白名单
     * 
     * @param dataDirectory 插件数据目录
     * @param logger 日志记录器
     * @throws IOException 配置加载失败时抛出（调用方需决定如何处理）
     */
    public static void initialize(Path dataDirectory, Logger logger) throws IOException {
        ProxyIdentityConfig.loadOrDefault(dataDirectory);
        
        if (ProxyIdentityConfig.debug) {
            PluginLogger.info(logger, "调试日志已启用。");
        }

        TrustedProxyGate.whitelist = ProxyIdentityConfig.trustedProxies;
        AccessControlGate.blockedIps = ProxyIdentityConfig.blockedIps;
        AccessControlGate.blockedCountries = ProxyIdentityConfig.blockedCountries;

        if (ProxyIdentityConfig.geoIpEnabled) {
            startGeoIpReloadTask(dataDirectory, logger);
        } else {
            AccessControlGate.countryResolver = address -> java.util.Optional.empty();
        }
        
        if (TrustedProxyGate.whitelist.size() == 0) {
            PluginLogger.warning(logger, "代理白名单为空。这将拒绝所有代理连接！");
        }
    }

    private static void startGeoIpReloadTask(Path dataDirectory, Logger logger) {
        Thread loader = new Thread(() -> {
            try {
                Path database = dataDirectory.resolve(ProxyIdentityConfig.geoIpDatabasePath);
                if (ProxyIdentityConfig.geoIpAutoUpdate
                        && GeoIpDatabaseUpdater.needsUpdate(database, ProxyIdentityConfig.geoIpUpdateDays)) {
                    PluginLogger.info(logger, "GeoIP 数据库需要同步，已启动后台任务。");
                    GeoIpDatabaseUpdater.update(database,
                            ProxyIdentityConfig.geoIpConnectTimeoutMs,
                            ProxyIdentityConfig.geoIpReadTimeoutMs,
                            logger);
                }
                if (!Files.exists(database)) {
                    PluginLogger.warning(logger, "未找到 GeoIP 数据库文件: " + database);
                    AccessControlGate.countryResolver = address -> java.util.Optional.empty();
                    return;
                }
                AccessControlGate.countryResolver = new GeoIpCountryDatabaseResolver(database);
                PluginLogger.info(logger, "GeoIP 国家库已加载: " + database);
            } catch (Throwable t) {
                PluginLogger.jul(logger, Level.WARNING, "加载 GeoIP 国家库失败，将仅启用 IP 封禁", t);
                AccessControlGate.countryResolver = address -> java.util.Optional.empty();
            }
        }, "ProxyIdentity-GeoIP-Loader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 安全加载配置（捕获异常并记录警告）
     * 
     * @param dataDirectory 插件数据目录
     * @param logger 日志记录器
     */
    public static void initializeSafely(Path dataDirectory, Logger logger) {
        try {
            initialize(dataDirectory, logger);
        } catch (IOException e) {
            PluginLogger.jul(logger, Level.WARNING, "加载配置失败，将使用默认配置", e);
        }
    }
}

