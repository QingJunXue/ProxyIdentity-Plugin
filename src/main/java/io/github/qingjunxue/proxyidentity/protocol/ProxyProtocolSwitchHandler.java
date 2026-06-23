/*
 * Copyright (C) 2020 Andy Li
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Lesser Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package io.github.qingjunxue.proxyidentity.protocol;

import java.net.SocketAddress;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutException;

import io.github.qingjunxue.proxyidentity.ProxyIdentityConfig;
import io.github.qingjunxue.proxyidentity.protocol.ProxyProtocolHeaderParser.Result;
import io.github.qingjunxue.proxyidentity.protocol.ProxyProtocolHeaderParser.State;
import io.github.qingjunxue.proxyidentity.security.AccessControlGate;
import io.github.qingjunxue.proxyidentity.util.PluginLogger;

public class ProxyProtocolSwitchHandler extends ByteToMessageDecoder {
    private final Logger logger;
    private final ChannelHandler haproxyHandler;
    private ScheduledFuture<?> timeoutTask;

    {
        setSingleDecode(true);
    }

    public ProxyProtocolSwitchHandler(Logger logger, ChannelHandler haproxyHandler) {
        this.logger = logger;
        this.haproxyHandler = haproxyHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        if (ProxyIdentityConfig.proxyProtocolEnabled && ProxyIdentityConfig.handshakeTimeoutMs > 0) {
            timeoutTask = ctx.channel().eventLoop().schedule(() -> {
                if (ctx.channel().isActive() && ctx.pipeline().get(this.getClass()) != null) {
                    SocketAddress addr = ctx.channel().remoteAddress();
                    if (logger != null && ProxyIdentityConfig.debug) {
                        PluginLogger.jul(logger, Level.FINE, "连接未在 " + ProxyIdentityConfig.handshakeTimeoutMs + "ms 内完成 PROXY protocol 握手，主动关闭: " + addr, null);
                    }
                    ctx.close();
                }
            }, ProxyIdentityConfig.handshakeTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTimeout() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ReadTimeoutException) {
            cancelTimeout();
            if (logger != null && ProxyIdentityConfig.debug) {
                PluginLogger.jul(logger, Level.FINE, "初始连接读取超时，主动关闭: " + ctx.channel().remoteAddress(), null);
            }
            ctx.close();
            return;
        }
        super.exceptionCaught(ctx, cause);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            byte[] bytes = new byte[in.readableBytes()];
            in.getBytes(in.readerIndex(), bytes);
            if (!ProxyIdentityConfig.proxyProtocolEnabled) {
                cancelTimeout();
                ctx.pipeline().remove(this);
                return;
            }
            Result detectionResult = ProxyProtocolHeaderParser.detect(bytes, bytes.length);
            if (detectionResult.state() == State.NEEDS_MORE_DATA) {
                return;
            }
            if (detectionResult.state() == State.INVALID) {
                cancelTimeout();
                if (ProxyIdentityConfig.requireProxyHeader) {
                    if (logger != null && ProxyIdentityConfig.debug) {
                        PluginLogger.jul(logger, Level.FINE, "连接未携带 PROXY protocol 头，已关闭: " + ctx.channel().remoteAddress(), null);
                    }
                    ctx.close();
                    return;
                }
                ctx.pipeline().remove(this);
                return;
            }

            cancelTimeout();
            if ((detectionResult.version() == 1 && !ProxyIdentityConfig.acceptV1)
                    || (detectionResult.version() == 2 && !ProxyIdentityConfig.acceptV2)) {
                ctx.close();
                return;
            }
            SocketAddress addr = ctx.channel().remoteAddress();
            if (!AccessControlGate.check(addr, detectionResult.sourceAddress())) {
                AccessControlGate.logIfBlocked(logger, addr, detectionResult.sourceAddress());
                ctx.close();
                return;
            }

            ChannelPipeline pipeline = ctx.pipeline();
            try {
                pipeline.replace(this, "haproxy-decoder", new HAProxyMessageDecoder());
            } catch (IllegalArgumentException ignored) {
                pipeline.remove(this);
            }

            if (haproxyHandler != null) {
                try {
                    pipeline.addAfter("haproxy-decoder", "haproxy-handler", haproxyHandler);
                } catch (IllegalArgumentException ignored) {
                    // handler already exists
                } catch (NoSuchElementException e) {
                    if (pipeline.get("timeout") != null) {
                        pipeline.addAfter("timeout", "haproxy-decoder", new HAProxyMessageDecoder());
                        pipeline.addAfter("haproxy-decoder", "haproxy-handler", haproxyHandler);
                    } else {
                        pipeline.addFirst("haproxy-handler", haproxyHandler);
                        pipeline.addFirst("haproxy-decoder", new HAProxyMessageDecoder());
                    }
                }
            }
        } catch (Throwable t) {
            cancelTimeout();
            PluginLogger.jul(logger, Level.WARNING, "检测代理时发生异常", t);
        }
    }
}

