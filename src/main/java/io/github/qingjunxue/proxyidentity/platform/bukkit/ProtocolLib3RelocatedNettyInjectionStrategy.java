package io.github.qingjunxue.proxyidentity.platform.bukkit;

import com.comphenix.protocol.utility.MinecraftReflection;
import io.github.qingjunxue.proxyidentity.GuardConfig;
import io.github.qingjunxue.proxyidentity.ProxyProtocolHeaderParser;
import io.github.qingjunxue.proxyidentity.TrustedProxyList;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProtocolLib3RelocatedNettyInjectionStrategy implements ProtocolLibInjectionStrategy {
    private static final String SERVER_HANDLER_NAME = "proxy-identity-server";
    private static final String CONNECTION_HANDLER_NAME = "proxy-identity";

    private final Logger logger;
    private final List<Object> serverChannels = new ArrayList<>();

    private Class<?> channelHandlerClass;
    private Class<?> channelInboundHandlerClass;
    private Class<?> channelFutureClass;

    public ProtocolLib3RelocatedNettyInjectionStrategy(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void inject() throws ReflectiveOperationException {
        try {
            uninject();
        } catch (Throwable ignored) {
        }

        channelHandlerClass = Class.forName("net.minecraft.util.io.netty.channel.ChannelHandler");
        channelInboundHandlerClass = Class.forName("net.minecraft.util.io.netty.channel.ChannelInboundHandler");
        channelFutureClass = Class.forName("net.minecraft.util.io.netty.channel.ChannelFuture");

        Object serverConnection = getServerConnection();
        boolean injectedAny = false;
        for (Field field : serverConnection.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(serverConnection);
            if (!(value instanceof List<?>)) {
                continue;
            }

            List<?> list = (List<?>) value;
            synchronized (list) {
                for (Object item : list) {
                    if (!channelFutureClass.isInstance(item)) {
                        continue;
                    }
                    Object channel = invoke(item, "channel");
                    if (addServerHandler(channel)) {
                        serverChannels.add(channel);
                        injectedAny = true;
                    }
                }
            }
        }

        if (!injectedAny) {
            throw new IllegalStateException("Unable to locate Bukkit 1.7.x server channels for PROXY protocol guard");
        }
        logger.info("已启用 Bukkit 1.7.x / ProtocolLib 3.x 专用 PROXY protocol 注入。");
        if (GuardConfig.debug) {
            logger.info("已向 " + serverChannels.size() + " 个服务端通道注入 PROXY protocol guard。");
        }
    }

    @Override
    public void uninject() {
        for (Object channel : serverChannels) {
            try {
                Object pipeline = invoke(channel, "pipeline");
                if (pipelineGet(pipeline, SERVER_HANDLER_NAME) != null) {
                    pipelineRemove(pipeline, SERVER_HANDLER_NAME);
                }
            } catch (Throwable ignored) {
            }
        }
        serverChannels.clear();
    }

    private Object getServerConnection() throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        Method getServer = craftServer.getClass().getMethod("getServer");
        Object minecraftServer = getServer.invoke(craftServer);
        Class<?> serverConnectionClass = MinecraftReflection.getServerConnectionClass();

        Object serverConnection = findServerConnectionByMethod(minecraftServer, serverConnectionClass);
        if (serverConnection != null) {
            return serverConnection;
        }

        serverConnection = findServerConnectionByField(minecraftServer, serverConnectionClass,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (serverConnection != null) {
            return serverConnection;
        }

        throw new NoSuchFieldException("No ServerConnection found on " + minecraftServer.getClass().getName()
                + " or its superclasses");
    }

    private Object findServerConnectionByMethod(Object minecraftServer, Class<?> serverConnectionClass)
            throws ReflectiveOperationException {
        Class<?> cursor = minecraftServer.getClass();
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                if (!serverConnectionClass.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                method.setAccessible(true);
                Object serverConnection = method.invoke(minecraftServer);
                if (serverConnection != null) {
                    return serverConnection;
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private Object findServerConnectionByField(Object root, Class<?> serverConnectionClass, Set<Object> visited, int depth)
            throws IllegalAccessException {
        if (root == null || depth > 3 || !visited.add(root)) {
            return null;
        }

        Class<?> cursor = root.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(root);
                if (value == null) {
                    continue;
                }
                if (serverConnectionClass.isInstance(value)) {
                    return value;
                }
            }
            cursor = cursor.getSuperclass();
        }

        cursor = root.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                Class<?> type = field.getType();
                if (type.isPrimitive()
                        || type.isArray()
                        || type.getName().startsWith("java.")
                        || type.getName().startsWith("javax.")) {
                    continue;
                }
                field.setAccessible(true);
                Object nested = field.get(root);
                Object serverConnection = findServerConnectionByField(nested, serverConnectionClass, visited, depth + 1);
                if (serverConnection != null) {
                    return serverConnection;
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private boolean addServerHandler(Object serverChannel) throws ReflectiveOperationException {
        Object pipeline = invoke(serverChannel, "pipeline");
        if (pipelineGet(pipeline, SERVER_HANDLER_NAME) != null) {
            return false;
        }
        pipelineAddFirst(pipeline, SERVER_HANDLER_NAME, createServerChannelHandler());
        return true;
    }

    private Object createServerChannelHandler() {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("channelRead".equals(method.getName()) && args != null && args.length == 2) {
                Object ctx = args[0];
                Object msg = args[1];
                Object childPipeline = invoke(msg, "pipeline");
                if (pipelineGet(childPipeline, CONNECTION_HANDLER_NAME) == null) {
                    pipelineAddBeforeOrFirst(childPipeline, "decoder", CONNECTION_HANDLER_NAME, createConnectionHandler());
                }
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "fireChannelRead",
                        new Class<?>[] { Object.class }, msg);
                return null;
            }
            return handlePassThrough(method, args);
        };
        return Proxy.newProxyInstance(channelInboundHandlerClass.getClassLoader(),
                new Class<?>[] { channelInboundHandlerClass }, handler);
    }

    private Object createConnectionHandler() {
        InvocationHandler handler = new RelocatedProxyProtocolHandler();
        return Proxy.newProxyInstance(channelInboundHandlerClass.getClassLoader(),
                new Class<?>[] { channelInboundHandlerClass }, handler);
    }

    private final class RelocatedProxyProtocolHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("channelRead".equals(method.getName()) && args != null && args.length == 2) {
                handleChannelRead(proxy, args[0], args[1]);
                return null;
            }
            return handlePassThrough(method, args);
        }

        private void handleChannelRead(Object self, Object ctx, Object msg) throws Throwable {
            if (!isByteBuf(msg)) {
                if (GuardConfig.debug) {
                    logger.info("PROXY protocol guard 收到非 ByteBuf 消息 "
                            + (msg == null ? "null" : msg.getClass().getName())
                            + "; pipeline=" + pipelineNames(ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "pipeline")));
                }
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "fireChannelRead",
                        new Class<?>[] { Object.class }, msg);
                return;
            }

            byte[] readable = readBytes(msg, 232);
            ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(readable, readable.length);
            if (!GuardConfig.proxyProtocolEnabled) {
                Object pipeline = ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "pipeline");
                pipelineRemove(pipeline, self);
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "fireChannelRead",
                        new Class<?>[] { Object.class }, msg);
                return;
            }
            if (result.state() == ProxyProtocolHeaderParser.State.NEEDS_MORE_DATA) {
                return;
            }

            Object channel = ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "channel");
            Object pipeline = ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "pipeline");
            pipelineRemove(pipeline, self);

            if (result.state() == ProxyProtocolHeaderParser.State.INVALID) {
                if (GuardConfig.debug) {
                    logger.info("连接未携带 PROXY protocol 头，已按直连放行。");
                }
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "fireChannelRead",
                        new Class<?>[] { Object.class }, msg);
                return;
            }
            if ((result.version() == 1 && !GuardConfig.acceptV1) || (result.version() == 2 && !GuardConfig.acceptV2)) {
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "close");
                return;
            }

            SocketAddress remoteAddress = (SocketAddress) ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(
                    channel, "remoteAddress");
            if (!TrustedProxyList.check(remoteAddress)) {
                try {
                    TrustedProxyList.getWarningFor(remoteAddress).ifPresent(logger::warning);
                } finally {
                    ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "close");
                }
                return;
            }

            if (result.sourceAddress() != null) {
                setNetworkManagerAddress(pipeline, result.sourceAddress());
                if (GuardConfig.debug) {
                    logger.log(Level.INFO, "PROXY protocol {0}：设置真实远程地址 {1} -> {2}",
                            new Object[] { connectionIntent(readable, result), remoteAddress, result.sourceAddress() });
                }
            }

            ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(msg, "skipBytes",
                    new Class<?>[] { int.class }, result.headerLength());
            if (((Integer) ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(msg, "readableBytes")) > 0) {
                ProtocolLib3RelocatedNettyInjectionStrategy.this.invoke(ctx, "fireChannelRead",
                        new Class<?>[] { Object.class }, msg);
            } else {
                tryRelease(msg);
            }
        }
    }

    private String firstBytes(byte[] bytes, int length) {
        StringBuilder out = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            String hex = Integer.toHexString(bytes[i] & 0xFF).toUpperCase();
            if (hex.length() == 1) {
                out.append('0');
            }
            out.append(hex);
        }
        return out.toString();
    }

    private String connectionIntent(byte[] bytes, ProxyProtocolHeaderParser.Result proxyResult) {
        if (proxyResult.state() != ProxyProtocolHeaderParser.State.DETECTED) {
            return "unknown";
        }
        int offset = proxyResult.headerLength();
        if (offset >= bytes.length) {
            return "unknown";
        }

        VarInt packetLength = readVarInt(bytes, offset);
        if (packetLength == null) {
            return "unknown";
        }
        int packetStart = offset + packetLength.bytesRead;
        if (packetStart >= bytes.length) {
            return "unknown";
        }

        VarInt packetId = readVarInt(bytes, packetStart);
        if (packetId == null || packetId.value != 0) {
            return "unknown";
        }
        int cursor = packetStart + packetId.bytesRead;

        VarInt protocolVersion = readVarInt(bytes, cursor);
        if (protocolVersion == null) {
            return "unknown";
        }
        cursor += protocolVersion.bytesRead;

        VarInt hostLength = readVarInt(bytes, cursor);
        if (hostLength == null || hostLength.value < 0) {
            return "unknown";
        }
        cursor += hostLength.bytesRead + hostLength.value + 2;
        if (cursor >= bytes.length) {
            return "unknown";
        }

        VarInt nextState = readVarInt(bytes, cursor);
        if (nextState == null) {
            return "unknown";
        }
        if (nextState.value == 1) {
            return "服务器列表刷新(status ping)";
        }
        if (nextState.value == 2) {
            return "玩家登录(login)";
        }
        return "未知意图(" + nextState.value + ")";
    }

    private VarInt readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int position = 0;
        for (int i = 0; i < 5; i++) {
            int index = offset + i;
            if (index >= bytes.length) {
                return null;
            }
            int current = bytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return new VarInt(value, i + 1);
            }
            position += 7;
        }
        return null;
    }

    private static final class VarInt {
        private final int value;
        private final int bytesRead;

        private VarInt(int value, int bytesRead) {
            this.value = value;
            this.bytesRead = bytesRead;
        }
    }

    private Object handlePassThrough(Method method, Object[] args) throws ReflectiveOperationException {
        String name = method.getName();
        if (args == null || args.length == 0) {
            return defaultValue(method.getReturnType());
        }
        Object ctx = args[0];
        if ("channelRegistered".equals(name)) {
            invoke(ctx, "fireChannelRegistered");
        } else if ("channelUnregistered".equals(name)) {
            invoke(ctx, "fireChannelUnregistered");
        } else if ("channelActive".equals(name)) {
            invoke(ctx, "fireChannelActive");
        } else if ("channelInactive".equals(name)) {
            invoke(ctx, "fireChannelInactive");
        } else if ("channelReadComplete".equals(name)) {
            invoke(ctx, "fireChannelReadComplete");
        } else if ("userEventTriggered".equals(name) && args.length > 1) {
            invoke(ctx, "fireUserEventTriggered", new Class<?>[] { Object.class }, args[1]);
        } else if ("channelWritabilityChanged".equals(name)) {
            invoke(ctx, "fireChannelWritabilityChanged");
        } else if ("exceptionCaught".equals(name) && args.length > 1) {
            invoke(ctx, "fireExceptionCaught", new Class<?>[] { Throwable.class }, args[1]);
        }
        return defaultValue(method.getReturnType());
    }

    private boolean isByteBuf(Object value) {
        if (value == null) {
            return false;
        }
        try {
            value.getClass().getMethod("readableBytes");
            value.getClass().getMethod("readerIndex");
            value.getClass().getMethod("getByte", int.class);
            value.getClass().getMethod("skipBytes", int.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private byte[] readBytes(Object byteBuf, int maxBytes) throws ReflectiveOperationException {
        int readableBytes = (Integer) invoke(byteBuf, "readableBytes");
        int readerIndex = (Integer) invoke(byteBuf, "readerIndex");
        int length = Math.min(readableBytes, maxBytes);
        byte[] bytes = new byte[length];
        Method getByte = byteBuf.getClass().getMethod("getByte", int.class);
        getByte.setAccessible(true);
        for (int i = 0; i < length; i++) {
            bytes[i] = (Byte) getByte.invoke(byteBuf, readerIndex + i);
        }
        return bytes;
    }

    private void setNetworkManagerAddress(Object pipeline, InetSocketAddress address) throws ReflectiveOperationException {
        Class<?> networkManagerClass = MinecraftReflection.getNetworkManagerClass();
        Object networkManager = null;
        for (Object entryObject : (Iterable<?>) pipeline) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
            Object handler = entry.getValue();
            if (networkManagerClass.isInstance(handler)) {
                networkManager = handler;
                break;
            }
        }
        if (networkManager == null) {
            throw new IllegalArgumentException("NetworkManager not found in relocated Netty pipeline");
        }

        Field addressField = findSocketAddressField(networkManager.getClass());
        addressField.setAccessible(true);
        addressField.set(networkManager, address);
    }

    private Field findSocketAddressField(Class<?> type) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (SocketAddress.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException("No SocketAddress field found on " + type.getName());
    }

    private void pipelineAddFirst(Object pipeline, String name, Object handler) throws ReflectiveOperationException {
        Method addFirst = pipeline.getClass().getMethod("addFirst", String.class, channelHandlerClass);
        addFirst.setAccessible(true);
        addFirst.invoke(pipeline, name, handler);
    }

    private void pipelineAddBeforeOrFirst(Object pipeline, String baseName, String name, Object handler)
            throws ReflectiveOperationException {
        if (pipelineGet(pipeline, baseName) != null) {
            Method addBefore = pipeline.getClass().getMethod("addBefore",
                    String.class, String.class, channelHandlerClass);
            addBefore.setAccessible(true);
            addBefore.invoke(pipeline, baseName, name, handler);
            return;
        }
        pipelineAddFirst(pipeline, name, handler);
    }

    private Object pipelineNames(Object pipeline) {
        try {
            Method names = pipeline.getClass().getMethod("names");
            names.setAccessible(true);
            return names.invoke(pipeline);
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private Object pipelineGet(Object pipeline, String name) throws ReflectiveOperationException {
        Method get = pipeline.getClass().getMethod("get", String.class);
        get.setAccessible(true);
        return get.invoke(pipeline, name);
    }

    private void pipelineRemove(Object pipeline, String name) throws ReflectiveOperationException {
        try {
            Method remove = pipeline.getClass().getMethod("remove", String.class);
            remove.setAccessible(true);
            remove.invoke(pipeline, name);
        } catch (ReflectiveOperationException e) {
            if (!(e.getCause() instanceof NoSuchElementException)) {
                throw e;
            }
        }
    }

    private void pipelineRemove(Object pipeline, Object handler) throws ReflectiveOperationException {
        try {
            Method remove = pipeline.getClass().getMethod("remove", channelHandlerClass);
            remove.setAccessible(true);
            remove.invoke(pipeline, handler);
        } catch (ReflectiveOperationException e) {
            if (!(e.getCause() instanceof NoSuchElementException)) {
                throw e;
            }
        }
    }

    private void tryRelease(Object byteBuf) {
        try {
            invoke(byteBuf, "release");
        } catch (Throwable ignored) {
        }
    }

    private Object invoke(Object target, String name) throws ReflectiveOperationException {
        return invoke(target, name, new Class<?>[0]);
    }

    private Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
