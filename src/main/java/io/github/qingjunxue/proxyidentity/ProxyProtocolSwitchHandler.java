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
package io.github.qingjunxue.proxyidentity;

import java.net.SocketAddress;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;

public class ProxyProtocolSwitchHandler extends ByteToMessageDecoder {
    private final Logger logger;
    private final ChannelHandler haproxyHandler;

    {
        setSingleDecode(true);
    }

    public ProxyProtocolSwitchHandler(Logger logger, ChannelHandler haproxyHandler) {
        this.logger = logger;
        this.haproxyHandler = haproxyHandler;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            if (!GuardConfig.proxyProtocolEnabled) {
                ctx.pipeline().remove(this);
                return;
            }
            ProtocolDetectionResult<HAProxyProtocolVersion> detectionResult =
                    HAProxyMessageDecoder.detectProtocol(in);
            switch (detectionResult.state()) {
                case NEEDS_MORE_DATA:
                    return;
                case INVALID:
                    ctx.pipeline().remove(this);
                    break;
                case DETECTED:
                default:
                    HAProxyProtocolVersion version = detectionResult.detectedProtocol();
                    if ((version == HAProxyProtocolVersion.V1 && !GuardConfig.acceptV1)
                            || (version == HAProxyProtocolVersion.V2 && !GuardConfig.acceptV2)) {
                        ctx.close();
                        return;
                    }
                    SocketAddress addr = ctx.channel().remoteAddress();
                    if (!TrustedProxyList.check(addr)) {
                        try {
                            TrustedProxyList.getWarningFor(addr).ifPresent(logger::warning);
                        } finally {
                            ctx.close();
                        }
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
                    break;
            }
        } catch (Throwable t) {
            if (logger != null)
                logger.log(Level.WARNING, "检测代理时发生异常", t);
            else
                t.printStackTrace();
        }
    }
}
