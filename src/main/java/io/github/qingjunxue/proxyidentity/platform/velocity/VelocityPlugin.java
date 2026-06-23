package io.github.qingjunxue.proxyidentity.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.github.qingjunxue.proxyidentity.PlatformBootstrap;
import io.github.qingjunxue.proxyidentity.ProxyIdentityConfig;
import io.github.qingjunxue.proxyidentity.TelemetryCharts;
import io.github.qingjunxue.proxyidentity.protocol.ProxyProtocolSwitchHandler;
import io.github.qingjunxue.proxyidentity.util.LoggerBridge;
import io.github.qingjunxue.proxyidentity.util.ReflectiveAccess;
import io.github.qingjunxue.proxyidentity.util.PipelineInjector;
import io.github.qingjunxue.proxyidentity.util.PluginLogger;
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

import static io.github.qingjunxue.proxyidentity.util.ReflectiveAccess.sneakyThrow;

@Plugin(
        id = "proxyidentity",
        name = "ProxyIdentity",
        version = "1.2.0",
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
        // 使用统一的启动流程
        try {
            PlatformBootstrap.initialize(this.dataDirectory, LoggerBridge.createJulToSlf4jBridge(logger));
        } catch (IOException e) {
            PluginLogger.warn(logger, "加载配置失败，将使用默认配置", e);
        }

        inject();

        try {
            Metrics metrics = metricsFactory.make(this, 32100);
            metrics.addCustomChart(TelemetryCharts.createWhitelistCountChart());
        } catch (Throwable t) {
            PluginLogger.warn(logger, "启动统计上报失败", t);
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
        replaceInitializer(holder, holderType, newInitializer);
        PluginLogger.info(logger, "已静默替换通道初始化器。");
    }

    static void replaceInitializer(Object holder, Class<?> holderType, ChannelInitializer<?> newInitializer)
            throws ReflectiveOperationException {
        for (Field field : holderType.getDeclaredFields()) {
            if (ChannelInitializer.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(holder, newInitializer);
                return;
            }
        }
        throw new NoSuchFieldException("未找到 Velocity 通道初始化器字段：" + holderType.getName());
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
            this.detectorLogger = LoggerBridge.createJulToSlf4jBridge(logger);
            this.delegate = delegate;
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
            if (!PipelineInjector.canInject(pipeline, "proxyidentity"))
                return;

            HAProxyMessageDecoder haproxyDecoder = pipeline.get(HAProxyMessageDecoder.class);
            if (haproxyDecoder != null) {
                pipeline.remove(haproxyDecoder);
                addDetector(pipeline);
                addTimeoutTrap(pipeline);
                return;
            }

            addDetector(pipeline);
            addTimeoutTrap(pipeline);
        }

        private void addDetector(ChannelPipeline pipeline) {
            ProxyProtocolSwitchHandler detector = new ProxyProtocolSwitchHandler(detectorLogger, null);
            String inboundName = findFirstNonTimeoutInboundHandlerName(pipeline);
            if (inboundName != null) {
                pipeline.addBefore(inboundName, "proxyidentity", detector);
                return;
            }

            String timeoutName = findTimeoutHandlerName(pipeline);
            if (timeoutName != null) {
                pipeline.addAfter(timeoutName, "proxyidentity", detector);
                return;
            }

            String decoderName = findFirstDecoderName(pipeline);
            if (decoderName != null) {
                pipeline.addBefore(decoderName, "proxyidentity", detector);
                return;
            }

            throw new RuntimeException("未找到可插入 PROXY protocol 检测器的 Velocity 解码器：" + pipeline.names());
        }

        private void addTimeoutTrap(ChannelPipeline pipeline) {
            if (pipeline.get("proxyidentity-timeout") != null) {
                return;
            }
            String timeoutName = findTimeoutHandlerName(pipeline);
            if (timeoutName != null) {
                pipeline.addAfter(timeoutName, "proxyidentity-timeout", new VelocityTimeoutHandler(detectorLogger));
            }
        }

        private String findTimeoutHandlerName(ChannelPipeline pipeline) {
            if (pipeline.get("timeout") != null) {
                return "timeout";
            }
            if (pipeline.get("read-timeout") != null) {
                return "read-timeout";
            }
            for (Map.Entry<String, ChannelHandler> entry : pipeline) {
                if (entry.getValue() instanceof ReadTimeoutHandler) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private String findFirstNonTimeoutInboundHandlerName(ChannelPipeline pipeline) {
            for (Map.Entry<String, ChannelHandler> entry : pipeline) {
                if (isTimeoutHandler(entry.getKey(), entry.getValue())) {
                    continue;
                }
                if (entry.getValue() instanceof ChannelInboundHandler) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private boolean isTimeoutHandler(String name, ChannelHandler handler) {
            return "timeout".equals(name) || "read-timeout".equals(name) || handler instanceof ReadTimeoutHandler;
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

    static final class VelocityTimeoutHandler extends ChannelInboundHandlerAdapter {
        private final java.util.logging.Logger logger;

        VelocityTimeoutHandler(java.util.logging.Logger logger) {
            this.logger = logger;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof ReadTimeoutException) {
                if (logger != null && ProxyIdentityConfig.debug) {
                    PluginLogger.jul(logger, java.util.logging.Level.FINE, "Velocity 初始连接读取超时，已静默关闭: " + ctx.channel().remoteAddress(), null);
                }
                ctx.close();
                return;
            }
            super.exceptionCaught(ctx, cause);
        }
    }
}

