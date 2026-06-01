package io.github.qingjunxue.proxyidentity;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyProtocolSwitchHandlerTest {
    @AfterEach
    void resetWhitelist() {
        TrustedProxyList.whitelist = new TrustedProxyList(Collections.emptyList());
    }

    @Test
    void fallbackPipelineKeepsHaproxyHandlerAfterDecoder() {
        TrustedProxyList.whitelist = null;
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("timeout", new ChannelInboundHandlerAdapter());
        pipeline.addLast("proxy-identity", new ProxyProtocolSwitchHandler(null, new ChannelInboundHandlerAdapter()));

        channel.writeInbound(Unpooled.copiedBuffer(
                "PROXY TCP4 198.51.100.1 203.0.113.1 12345 25565\r\n".getBytes()));

        assertTrue(pipeline.names().indexOf("haproxy-decoder") < pipeline.names().indexOf("haproxy-handler"));
    }
}
