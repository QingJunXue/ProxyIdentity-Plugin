package io.github.qingjunxue.proxyidentity.protocol;

import io.github.qingjunxue.proxyidentity.ProxyIdentityConfig;
import io.github.qingjunxue.proxyidentity.security.TrustedProxyGate;
import io.github.qingjunxue.proxyidentity.security.TrustedProxyList;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyProtocolSwitchHandlerTest {
    @AfterEach
    void resetWhitelist() {
        ProxyIdentityConfig.proxyProtocolEnabled = true;
        ProxyIdentityConfig.requireProxyHeader = true;
        ProxyIdentityConfig.acceptV1 = true;
        ProxyIdentityConfig.acceptV2 = true;
        ProxyIdentityConfig.handshakeTimeoutMs = 3000;
        TrustedProxyGate.whitelist = new TrustedProxyList(Collections.emptyList());
    }

    @Test
    void fallbackPipelineKeepsHaproxyHandlerAfterDecoder() {
        TrustedProxyGate.whitelist = null;
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("timeout", new ChannelInboundHandlerAdapter());
        pipeline.addLast("proxyidentity", new ProxyProtocolSwitchHandler(null, new ChannelInboundHandlerAdapter()));

        channel.writeInbound(Unpooled.copiedBuffer(
                "PROXY TCP4 198.51.100.1 203.0.113.1 12345 25565\r\n".getBytes()));

        assertTrue(pipeline.names().indexOf("haproxy-decoder") < pipeline.names().indexOf("haproxy-handler"));
    }

    @Test
    void closesPlainInputWhenProxyHeaderIsRequired() {
        ProxyIdentityConfig.requireProxyHeader = true;
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolSwitchHandler(null, null));

        channel.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\n".getBytes()));

        assertFalse(channel.isOpen());
    }

    @Test
    void allowsPlainInputWhenProxyHeaderIsOptional() {
        ProxyIdentityConfig.requireProxyHeader = false;
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolSwitchHandler(null, null));

        channel.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\n".getBytes()));

        assertTrue(channel.isOpen());
        assertFalse(channel.pipeline().names().contains("proxyidentity"));
    }

    @Test
    void closesReadTimeoutWithoutPropagatingException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolSwitchHandler(null, null));

        channel.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE);

        assertFalse(channel.isOpen());
        assertDoesNotThrow(channel::checkException);
    }

    @Test
    void catchesReadTimeoutFromPreviousPipelineHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("timeout", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
            }
        });
        pipeline.addLast("proxyidentity", new ProxyProtocolSwitchHandler(null, null));
        pipeline.addLast("initial-connection", new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                throw new AssertionError("读取超时异常不应继续传递到初始连接处理器", cause);
            }
        });

        channel.pipeline().fireChannelActive();

        assertFalse(channel.isOpen());
        assertDoesNotThrow(channel::checkException);
    }
}
