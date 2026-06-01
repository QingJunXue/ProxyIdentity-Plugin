package io.github.qingjunxue.proxyidentity.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.github.qingjunxue.proxyidentity.ProxyProtocolSwitchHandler;
import io.github.qingjunxue.proxyidentity.TelemetryCharts;
import io.github.qingjunxue.proxyidentity.TrustedProxyList;
import io.github.qingjunxue.proxyidentity.ReflectiveAccess;
import io.github.qingjunxue.proxyidentity.GuardConfig;
import org.bstats.velocity.Metrics;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static io.github.qingjunxue.proxyidentity.ReflectiveAccess.sneakyThrow;

@Plugin(
        id = "proxy-identity",
        name = "ProxyIdentity",
        version = "1.0.0",
        description = "允许同时接受直连与通过 HAProxy 转发的代理连接。",
        authors = {"ACJ_DragonDream"},
        url = "https://github.com/QingJunXue/proxy-identity"
)
public final class VelocityPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws ReflectiveOperationException, IOException {
        // 加载通用配置（包含 debug 开关）
        GuardConfig.loadOrDefault(this.dataDirectory);

        TrustedProxyList.whitelist = GuardConfig.trustedProxies;
        if (TrustedProxyList.whitelist.size() == 0) {
            logger.warn("代理白名单为空。这将拒绝所有代理连接！");
        }

        inject();

        try {
            Metrics metrics = metricsFactory.make(this, 14442);
            metrics.addCustomChart(TelemetryCharts.createWhitelistCountChart());
        } catch (Throwable t) {
            logger.warn("启动统计上报失败", t);
        }
    }

    private void inject() throws ReflectiveOperationException {
        Class<?> cmType = Class.forName("com.velocitypowered.proxy.network.ConnectionManager");
        Field cmField = ReflectiveAccess.getFirstDeclaringFieldByType(this.server.getClass(), cmType);
        cmField.setAccessible(true);
        Object connectionManager = cmField.get(this.server);

        Object holder = cmType.getMethod("getServerChannelInitializer").invoke(connectionManager);
        Class<?> holderType = holder.getClass();

        @SuppressWarnings("unchecked") ChannelInitializer<Channel> originalInitializer =
            (ChannelInitializer<Channel>) holderType.getMethod("get").invoke(holder);

        DetectorInitializer<Channel> newInitializer =
            new DetectorInitializer<>(logger, originalInitializer);
        MethodHandle set = MethodHandles.lookup().unreflect(holderType.getMethod("set", ChannelInitializer.class));
        try {
            logger.info("正在替换通道初始化器；可以安全忽略下一条警告。");
            // We use MethodHandle here because it has a cleaner stacktrace
            // for ChannelInitializerHolder.set() to display
            set.invoke(holder, newInitializer);
        } catch (Throwable e) {
            sneakyThrow(e);
        }
    }

    static class DetectorInitializer<C extends Channel> extends ChannelInitializer<C> {
        static final MethodHandle INIT_CHANNEL;

        static {
            MethodHandle handle = null;
            try {
                Method m = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
                m.setAccessible(true);
                handle = MethodHandles.lookup().unreflect(m);
            } catch (ReflectiveOperationException e) {
                sneakyThrow(e);
            }
            INIT_CHANNEL = handle;
        }

        private final Logger logger;
        private final java.util.logging.Logger detectorLogger;
        private final ChannelInitializer<C> delegate;

        DetectorInitializer(@NotNull Logger logger, @NotNull ChannelInitializer<C> delegate) {
            this.logger = logger;
            this.detectorLogger = createDetectorLogger(logger);
            this.delegate = delegate;
        }

        private static java.util.logging.Logger createDetectorLogger(Logger logger) {
            java.util.logging.Logger detectorLogger = java.util.logging.Logger.getAnonymousLogger();
            detectorLogger.setUseParentHandlers(false);
            detectorLogger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (!isLoggable(record))
                        return;

                    Throwable thrown = record.getThrown();
                    String message = record.getMessage();
                    int level = record.getLevel().intValue();
                    if (level >= java.util.logging.Level.SEVERE.intValue())
                        logger.error(message, thrown);
                    else if (level >= java.util.logging.Level.WARNING.intValue())
                        logger.warn(message, thrown);
                    else if (level >= java.util.logging.Level.INFO.intValue())
                        logger.info(message, thrown);
                    else
                        logger.debug(message, thrown);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            return detectorLogger;
        }

        @Override
        public void initChannel(C ch) {
            try {
                INIT_CHANNEL.invoke(this.delegate, ch);
            } catch (Throwable e) {
                sneakyThrow(e);
                return;
            }

            ChannelPipeline pipeline = ch.pipeline();
            if (!ch.isOpen() || pipeline.get("proxy-identity") != null)
                return;

            HAProxyMessageDecoder haproxyDecoder = pipeline.get(HAProxyMessageDecoder.class);
            if (haproxyDecoder != null) {
                pipeline.replace(haproxyDecoder, "proxy-identity", new ProxyProtocolSwitchHandler(detectorLogger, null));
                return;
            }

            String decoderName = findFirstDecoderName(pipeline);
            if (decoderName != null) {
                pipeline.addBefore(decoderName, "proxy-identity", new ProxyProtocolSwitchHandler(detectorLogger, null));
                return;
            }

            throw new RuntimeException("未找到可插入 PROXY protocol 检测器的 Velocity 解码器：" + pipeline.names());
        }

        private String findFirstDecoderName(ChannelPipeline pipeline) {
            for (Map.Entry<String, ChannelHandler> entry : pipeline) {
                if (entry.getValue() instanceof ByteToMessageDecoder) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }
}
