package io.github.qingjunxue.proxyidentity.platform.bukkit;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.reflect.FuzzyReflection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.github.qingjunxue.proxyidentity.ProxyProtocolSwitchHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModernProtocolLibInjectionStrategy implements ProtocolLibInjectionStrategy {
    private final Logger logger;

    private Field handlerField;
    private ChannelInboundHandler injectorInitializer;
    private ChannelInboundHandler originalHandler;

    public ModernProtocolLibInjectionStrategy(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void inject() throws ReflectiveOperationException {
        try {
            this.uninject();
        } catch (Throwable ignored) {
        }

        Class<?> networkManagerInjectorClass = Class.forName(
                "com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector");
        Class<?> injectionChannelInitializerClass = Class.forName(
                "com.comphenix.protocol.injector.netty.manager.InjectionChannelInitializer");

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        Field injectorField = FuzzyReflection.fromObject(pm, true)
                .getFieldByType("networkManagerInjector", networkManagerInjectorClass);
        injectorField.setAccessible(true);
        Object networkManagerInjector = injectorField.get(pm);

        Field injectorInitializerField = FuzzyReflection.fromClass(networkManagerInjectorClass, true)
                .getFieldByType("pipelineInjectorHandler", injectionChannelInitializerClass);
        injectorInitializerField.setAccessible(true);
        this.injectorInitializer = (ChannelInboundHandler) injectorInitializerField.get(networkManagerInjector);

        this.handlerField = FuzzyReflection.fromClass(injectionChannelInitializerClass, true)
                .getFieldByType("handler", ChannelInboundHandler.class);
        handlerField.setAccessible(true);
        this.originalHandler = (ChannelInboundHandler) handlerField.get(injectorInitializer);

        ChannelInboundHandler myHandler = (ChannelInboundHandler) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[] { ChannelInboundHandler.class },
                (proxy, method, args) -> {
                    if ("channelActive".equals(method.getName())) {
                        ChannelHandlerContext ctx = (ChannelHandlerContext) args[0];
                        ctx.pipeline().remove((ChannelHandler) proxy)
                                .addFirst("protocol_lib_inbound_inject", originalHandler);

                        Object ret = method.invoke(originalHandler, args);
                        doInject(ctx.channel());
                        return ret;
                    }
                    return method.invoke(originalHandler, args);
                });
        handlerField.set(injectorInitializer, myHandler);
    }

    @Override
    public void uninject() throws ReflectiveOperationException {
        if (this.handlerField != null && this.injectorInitializer != null && this.originalHandler != null) {
            handlerField.set(injectorInitializer, this.originalHandler);
            this.injectorInitializer = null;
            this.originalHandler = null;
        }
    }

    void doInject(Channel ch) {
        if (ch.eventLoop().inEventLoop()) {
            try {
                ChannelPipeline pipeline = ch.pipeline();
                if (!ch.isOpen() || pipeline.get("proxy-identity") != null) {
                    return;
                }

                if (pipeline.get("haproxy-decoder") != null) {
                    pipeline.remove("haproxy-decoder");
                }

                ChannelHandler haproxyHandler;
                if (pipeline.get("haproxy-handler") != null) {
                    haproxyHandler = pipeline.remove("haproxy-handler");
                } else {
                    ChannelHandler networkManager = BukkitPlugin.getNetworkManager(pipeline);
                    haproxyHandler = new BukkitProxyAddressHandler(networkManager);
                }

                ProxyProtocolSwitchHandler detector = new ProxyProtocolSwitchHandler(logger, haproxyHandler);
                try {
                    pipeline.addAfter("timeout", "proxy-identity", detector);
                } catch (NoSuchElementException e) {
                    pipeline.addFirst("proxy-identity", detector);
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(Level.WARNING, "注入代理检测器时发生异常", t);
                } else {
                    t.printStackTrace();
                }
            }
        } else {
            ch.eventLoop().execute(() -> this.doInject(ch));
        }
    }
}