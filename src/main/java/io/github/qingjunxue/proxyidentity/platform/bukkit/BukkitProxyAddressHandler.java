package io.github.qingjunxue.proxyidentity.platform.bukkit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.logging.Level;

import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import static io.github.qingjunxue.proxyidentity.ReflectiveAccess.sneakyThrow;
import io.github.qingjunxue.proxyidentity.GuardConfig;

class BukkitProxyAddressHandler extends SimpleChannelInboundHandler<HAProxyMessage> {
    private static volatile MethodHandle freeAddressSetter;
    private final MethodHandle addressSetter;

    public BukkitProxyAddressHandler(ChannelHandler networkManager) {
        if (freeAddressSetter == null) {
            synchronized (BukkitProxyAddressHandler.class) {
                if (freeAddressSetter == null) {
                    Field f = FuzzyReflection.fromClass(MinecraftReflection.getNetworkManagerClass(), true)
                            .getFieldByType("socketAddress", SocketAddress.class);
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }

                    try {
                        freeAddressSetter = MethodHandles.lookup().unreflectSetter(f);
                    } catch (IllegalAccessException e) {
                        sneakyThrow(e);
                        throw new AssertionError("不应到达的代码路径");
                    }
                }
            }
        }

        this.addressSetter = freeAddressSetter.bindTo(networkManager);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HAProxyMessage msg) {
        SocketAddress realAddress = new InetSocketAddress(msg.sourceAddress(), msg.sourcePort());
		if (GuardConfig.debug) {
			BukkitPlugin.logger.log(Level.INFO, "通过代理设置真实远程地址 {0} -> {1}",
					new Object[] { ctx.channel().remoteAddress(), realAddress });
		}
        try {
            addressSetter.invokeExact(realAddress);
        } catch (Throwable e) {
            sneakyThrow(e);
        }
    }
}