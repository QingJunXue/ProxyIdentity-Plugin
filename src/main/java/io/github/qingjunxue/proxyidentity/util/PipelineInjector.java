package io.github.qingjunxue.proxyidentity.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.NoSuchElementException;

/**
 * Netty Pipeline 操作的通用工具，封装常见的注入/移除模式。
 */
public final class PipelineInjector {
    private PipelineInjector() {}

    /** 在 baseName 之后添加 handler，若 baseName 不存在则添加到首部。 */
    public static boolean addAfterOrFirst(ChannelPipeline pipeline, String baseName, String name, ChannelHandler handler) {
        try {
            pipeline.addAfter(baseName, name, handler);
            return true;
        } catch (NoSuchElementException e) {
            pipeline.addFirst(name, handler);
            return false;
        }
    }

    /** 在 baseName 之前添加 handler，若 baseName 不存在则添加到首部。 */
    public static boolean addBeforeOrFirst(ChannelPipeline pipeline, String baseName, String name, ChannelHandler handler) {
        try {
            pipeline.addBefore(baseName, name, handler);
            return true;
        } catch (NoSuchElementException e) {
            pipeline.addFirst(name, handler);
            return false;
        }
    }

    /** 通道仍打开且尚未注入指定 handler 时返回 true。 */
    public static boolean canInject(ChannelPipeline pipeline, String handlerName) {
        return pipeline.channel().isOpen() && pipeline.get(handlerName) == null;
    }

    /** 安全移除指定名称的 handler，并返回被移除的实例。不存在返回 null。 */
    public static ChannelHandler removeIfExists(ChannelPipeline pipeline, String handlerName) {
        ChannelHandler handler = pipeline.get(handlerName);
        if (handler != null) {
            try {
                pipeline.remove(handlerName);
                return handler;
            } catch (NoSuchElementException ignored) {
            }
        }
        return null;
    }
}