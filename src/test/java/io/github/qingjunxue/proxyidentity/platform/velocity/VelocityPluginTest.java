package io.github.qingjunxue.proxyidentity.platform.velocity;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import com.velocitypowered.api.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityPluginTest {
    @Test
    void pluginAnnotationVersionMatchesMavenProjectVersion() throws Exception {
        String projectVersion = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File("pom.xml"))
                .getDocumentElement()
                .getElementsByTagName("version")
                .item(0)
                .getTextContent()
                .trim();

        assertEquals(projectVersion, VelocityPlugin.class.getAnnotation(Plugin.class).version());
    }

    @Test
    void replacesInitializerWithoutCallingVelocityWarningSetMethod() throws Exception {
        ChannelInitializer<EmbeddedChannel> original = new NoopInitializer();
        WarningHolder holder = new WarningHolder(original);
        ChannelInitializer<EmbeddedChannel> replacement = new NoopInitializer();

        VelocityPlugin.replaceInitializer(holder, WarningHolder.class, replacement);

        assertEquals(replacement, holder.get());
        assertEquals(0, holder.setCalls);
    }

    @Test
    void placesProxyIdentityAfterTimeoutWhenReplacingVelocityHaproxyDecoder() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("haproxy-decoder", new HAProxyMessageDecoder());
                ch.pipeline().addLast("timeout", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("minecraft-decoder", new ByteToMessageDecoderForTest());
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);

        ChannelPipeline pipeline = channel.pipeline();
        assertTrue(pipeline.names().indexOf("timeout") < pipeline.names().indexOf("proxyidentity"));
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("minecraft-decoder"));
    }

    @Test
    void placesProxyIdentityBeforeVelocityConnectionHandlerWhenConnectionPrecedesDecoder() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("timeout", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("minecraft-connection", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("minecraft-decoder", new ByteToMessageDecoderForTest());
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);

        ChannelPipeline pipeline = channel.pipeline();
        assertTrue(pipeline.names().indexOf("timeout") < pipeline.names().indexOf("proxyidentity"));
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("minecraft-connection"));
    }

    @Test
    void placesProxyIdentityAfterReadTimeoutHandlerWhenTimeoutNameDiffers() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("read-timeout", new ReadTimeoutHandler(30));
                ch.pipeline().addLast("minecraft-connection", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("minecraft-decoder", new ByteToMessageDecoderForTest());
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);

        ChannelPipeline pipeline = channel.pipeline();
        assertTrue(pipeline.names().indexOf("read-timeout") < pipeline.names().indexOf("proxyidentity"));
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("minecraft-connection"));
    }

    @Test
    void placesProxyIdentityBeforeVelocity350Build584Decoders() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("legacy-ping-decoder", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("frame-decoder", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("read-timeout", new ReadTimeoutHandler(30000));
                ch.pipeline().addLast("legacy-ping-encoder", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("frame-encoder", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("minecraft-decoder", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("minecraft-encoder", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("handler", new ChannelInboundHandlerAdapter());
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);

        ChannelPipeline pipeline = channel.pipeline();
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("legacy-ping-decoder"));
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("frame-decoder"));
        assertTrue(pipeline.names().indexOf("read-timeout") < pipeline.names().indexOf("proxyidentity-timeout"));
        assertTrue(pipeline.names().indexOf("proxyidentity-timeout") < pipeline.names().indexOf("handler"));
    }

    @Test
    void catchesReadTimeoutAfterVelocity350Build605TimeoutHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("legacy-ping-decoder", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("frame-decoder", new ByteToMessageDecoderForTest());
                ch.pipeline().addLast("read-timeout", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
                        ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
                    }
                });
                ch.pipeline().addLast("handler", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                        throw new AssertionError("ReadTimeoutException should not reach Velocity initial connection", cause);
                    }
                });
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);
        channel.pipeline().fireChannelActive();

        assertFalse(channel.isOpen());
        assertDoesNotThrow(channel::checkException);
    }

    @Test
    void placesProxyIdentityBeforeConnectionHandlerEvenWhenTimeoutIsLater() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInitializer<EmbeddedChannel> delegate = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast("minecraft-connection", new ChannelInboundHandlerAdapter());
                ch.pipeline().addLast("read-timeout", new ReadTimeoutHandler(30));
                ch.pipeline().addLast("minecraft-decoder", new ByteToMessageDecoderForTest());
            }
        };
        VelocityPlugin.DetectorInitializer<EmbeddedChannel> initializer =
                new VelocityPlugin.DetectorInitializer<>(noopLogger(), delegate);

        initializer.initChannel(channel);

        ChannelPipeline pipeline = channel.pipeline();
        assertTrue(pipeline.names().indexOf("proxyidentity") < pipeline.names().indexOf("minecraft-connection"));
    }

    private static Logger noopLogger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == String.class) {
                        return "test";
                    }
                    return null;
                });
    }

    private static final class ByteToMessageDecoderForTest extends ByteToMessageDecoder {
        @Override
        protected void decode(io.netty.channel.ChannelHandlerContext ctx, io.netty.buffer.ByteBuf in, java.util.List<Object> out) {
        }
    }

    private static final class NoopInitializer extends ChannelInitializer<EmbeddedChannel> {
        @Override
        protected void initChannel(EmbeddedChannel ch) {
        }
    }

    private static final class WarningHolder {
        private ChannelInitializer<EmbeddedChannel> initializer;
        private int setCalls;

        private WarningHolder(ChannelInitializer<EmbeddedChannel> initializer) {
            this.initializer = initializer;
        }

        public ChannelInitializer<EmbeddedChannel> get() {
            return initializer;
        }

        public void set(ChannelInitializer<EmbeddedChannel> initializer) {
            setCalls++;
            this.initializer = initializer;
        }
    }
}
