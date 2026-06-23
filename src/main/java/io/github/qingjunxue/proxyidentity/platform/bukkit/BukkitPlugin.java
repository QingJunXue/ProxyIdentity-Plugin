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
import io.github.qingjunxue.proxyidentity.util.PluginLogger;
import io.github.qingjunxue.proxyidentity.util.ReflectiveAccess;
import io.github.qingjunxue.proxyidentity.PlatformBootstrap;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;


import static io.github.qingjunxue.proxyidentity.util.ReflectiveAccess.sneakyThrow;

public final class BukkitPlugin extends JavaPlugin {
    static Logger logger;

    private ProtocolLibInjectionStrategy injectionStrategy;

    @Override
    public void onLoad() {
        logger = getLogger();
    }

    @Override
    public void onEnable() {
        PlatformBootstrap.initializeSafely(this.getDataFolder().toPath(), logger);

        Plugin protocolLib = getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            PluginLogger.jul(logger, Level.SEVERE, "缺少必要依赖 ProtocolLib。请安装与你的服务端版本兼容的 ProtocolLib 后再启用本插件。", null);
            this.setEnabled(false);
            return;
        }
        String plVersion = protocolLib.getDescription().getVersion();

        try {
            if (ReflectiveAccess.hasClass("com.comphenix.protocol.injector.netty.NettyProtocolInjector")
                    && ReflectiveAccess.hasClass("net.minecraft.util.io.netty.channel.Channel")) {
                injectionStrategy = new ProtocolLib3RelocatedNettyInjectionStrategy(logger);
            } else if (ReflectiveAccess.hasClass("com.comphenix.protocol.injector.netty.ProtocolInjector")) {
                injectionStrategy = new LegacyProtocolLibInjectionStrategy(logger);
            } else if (ReflectiveAccess.hasClass(
                    "com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector")) {
                injectionStrategy = new ModernProtocolLibInjectionStrategy(logger);
            } else {
                PluginLogger.jul(logger, Level.SEVERE, "不支持的 ProtocolLib 版本 " + plVersion + "。", null);
                PluginLogger.jul(logger, Level.SEVERE, "当前 ProtocolLib 版本没有可识别的 Netty 注入入口。", null);
                this.setEnabled(false);
                return;
            }

            injectionStrategy.inject();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            PluginLogger.jul(logger, Level.SEVERE, "当前 ProtocolLib 版本缺少必要的 Netty 注入 API：" + plVersion, e);
            this.setEnabled(false);
        } catch (ReflectiveOperationException e) {
            sneakyThrow(e);
        }

        try {
            Metrics metrics = new Metrics(this, 32098);
            metrics.addCustomChart(TelemetryCharts.createWhitelistCountChart());
            metrics.addCustomChart(new SimplePie(TelemetryCharts.KEY_PROTOCOLLIB_VERSION,
                    () -> ProtocolLibrary.getPlugin().getDescription().getVersion()));
        } catch (Throwable t) {
            PluginLogger.jul(logger, Level.WARNING, "启动统计上报失败", t);
        }
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

