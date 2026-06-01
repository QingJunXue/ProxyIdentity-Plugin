package io.github.qingjunxue.proxyidentity.platform.bukkit;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.comphenix.protocol.ProtocolLibrary;

import com.comphenix.protocol.utility.MinecraftReflection;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.github.qingjunxue.proxyidentity.TelemetryCharts;
import io.github.qingjunxue.proxyidentity.GuardConfig;
import io.github.qingjunxue.proxyidentity.TrustedProxyList;
import io.github.qingjunxue.proxyidentity.ReflectiveAccess;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;


import static io.github.qingjunxue.proxyidentity.ReflectiveAccess.sneakyThrow;

public final class BukkitPlugin extends JavaPlugin {
    static Logger logger;

    private ProtocolLibInjectionStrategy injectionStrategy;

    @Override
    public void onLoad() {
        logger = getLogger();
    }

    @Override
    public void onEnable() {
        try {
            // 加载通用配置（包含 debug 开关）
            GuardConfig.loadOrDefault(this.getDataFolder().toPath());
            if (GuardConfig.debug) {
                logger.info("调试日志已启用。");
            }
		} catch (IOException e) {
			logger.log(Level.WARNING, "加载配置失败，将使用默认配置", e);
		}

        TrustedProxyList.whitelist = GuardConfig.trustedProxies;
        if (TrustedProxyList.whitelist.size() == 0) {
            logger.warning("代理白名单为空。这将拒绝所有代理连接！");
        }

        Plugin protocolLib = getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            logger.severe("缺少必要依赖 ProtocolLib。请安装与你的服务端版本兼容的 ProtocolLib 后再启用本插件。");
            this.setEnabled(false);
            return;
        }
        String plVersion = protocolLib.getDescription().getVersion();

        try {
            if (ReflectiveAccess.hasClass("com.comphenix.protocol.injector.netty.NettyProtocolInjector")
                    && ReflectiveAccess.hasClass("net.minecraft.util.io.netty.channel.Channel")) {
                injectionStrategy = createProtocolLib3RelocatedNettyInjectionStrategy();
            } else if (ReflectiveAccess.hasClass("com.comphenix.protocol.injector.netty.ProtocolInjector")) {
                injectionStrategy = createLegacyProtocolLibInjectionStrategy();
            } else if (ReflectiveAccess.hasClass(
                    "com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector")) {
                injectionStrategy = createModernProtocolLibInjectionStrategy();
            } else {
                logger.severe("不支持的 ProtocolLib 版本 " + plVersion + "。");
                logger.severe("当前 ProtocolLib 版本没有可识别的 Netty 注入入口。");
                this.setEnabled(false);
                return;
            }

            injectionStrategy.inject();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            logger.log(Level.SEVERE, "当前 ProtocolLib 版本缺少必要的 Netty 注入 API：" + plVersion, e);
            this.setEnabled(false);
        } catch (ReflectiveOperationException e) {
            sneakyThrow(e);
        }

        try {
            Metrics metrics = new Metrics(this, 12604);
            metrics.addCustomChart(TelemetryCharts.createWhitelistCountChart());
            metrics.addCustomChart(new SimplePie(TelemetryCharts.KEY_PROTOCOLLIB_VERSION,
                    () -> ProtocolLibrary.getPlugin().getDescription().getVersion()));
        } catch (Throwable t) {
            logger.log(Level.WARNING, "启动统计上报失败", t);
        }
    }

    private static ProtocolLibInjectionStrategy createLegacyProtocolLibInjectionStrategy() throws ReflectiveOperationException {
        return createProtocolLibInjectionStrategy("io.github.qingjunxue.proxyidentity.platform.bukkit.LegacyProtocolLibInjectionStrategy");
    }

    private static ProtocolLibInjectionStrategy createProtocolLib3RelocatedNettyInjectionStrategy() throws ReflectiveOperationException {
        return createProtocolLibInjectionStrategy("io.github.qingjunxue.proxyidentity.platform.bukkit.ProtocolLib3RelocatedNettyInjectionStrategy");
    }

    private static ProtocolLibInjectionStrategy createModernProtocolLibInjectionStrategy() throws ReflectiveOperationException {
        return createProtocolLibInjectionStrategy("io.github.qingjunxue.proxyidentity.platform.bukkit.ModernProtocolLibInjectionStrategy");
    }

    private static ProtocolLibInjectionStrategy createProtocolLibInjectionStrategy(String className) throws ReflectiveOperationException {
        return (ProtocolLibInjectionStrategy) Class.forName(className)
                .getConstructor(Logger.class)
                .newInstance(logger);
    }

    @Override
    public void onDisable() {
        if (injectionStrategy != null) {
            try {
                injectionStrategy.uninject();
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    static ChannelHandler getNetworkManager(ChannelPipeline pipeline) {
        Class<? extends ChannelHandler> networkManagerClass = (Class<? extends ChannelHandler>) MinecraftReflection.getNetworkManagerClass();
        ChannelHandler networkManager = null;
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (networkManagerClass.isAssignableFrom(entry.getValue().getClass())) {
                networkManager = entry.getValue();
                break;
            }
        }

        if (networkManager == null) {
            throw new IllegalArgumentException("NetworkManager not found in channel pipeline " + pipeline.names());
        }

        return networkManager;
    }
}
