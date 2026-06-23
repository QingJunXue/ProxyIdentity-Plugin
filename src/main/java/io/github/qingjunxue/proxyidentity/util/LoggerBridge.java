package io.github.qingjunxue.proxyidentity.util;

import org.slf4j.Logger;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * 跨日志框架的桥接工具。
 */
public final class LoggerBridge {
    private LoggerBridge() {}

    /**
     * 创建一个 JUL Logger，将所有日志转发到 SLF4J Logger（用于 Velocity）。
     */
    public static java.util.logging.Logger createJulToSlf4jBridge(Logger slf4jLogger) {
        java.util.logging.Logger julLogger = java.util.logging.Logger.getAnonymousLogger();
        julLogger.setUseParentHandlers(false);
        julLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!isLoggable(record)) {
                    return;
                }

                PluginLogger.relay(slf4jLogger, record.getLevel(), record.getMessage(), record.getThrown());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return julLogger;
    }
}
