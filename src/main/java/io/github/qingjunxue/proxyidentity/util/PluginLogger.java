package io.github.qingjunxue.proxyidentity.util;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件统一日志门面，负责跨平台输出与插件名前缀去重。
 */
public final class PluginLogger {
    private static final String PREFIX = "[ProxyIdentity] ";

    private PluginLogger() {}

    public static void fine(Logger logger, String message) {
        jul(logger, Level.FINE, message, null);
    }

    public static void info(Logger logger, String message) {
        jul(logger, Level.INFO, message, null);
    }

    public static void warning(Logger logger, String message) {
        jul(logger, Level.WARNING, message, null);
    }

    public static void warning(Logger logger, String message, Throwable throwable) {
        jul(logger, Level.WARNING, message, throwable);
    }

    public static void severe(Logger logger, String message) {
        jul(logger, Level.SEVERE, message, null);
    }

    public static void severe(Logger logger, String message, Throwable throwable) {
        jul(logger, Level.SEVERE, message, throwable);
    }

    public static void jul(Logger logger, Level level, String message, Throwable throwable) {
        if (logger == null) {
            fallback(level, message, throwable);
            return;
        }
        logger.log(level, format(logger, message), throwable);
    }

    public static void info(org.slf4j.Logger logger, String message) {
        if (logger == null) {
            fallback(Level.INFO, message, null);
            return;
        }
        logger.info(format(logger, message));
    }

    public static void warn(org.slf4j.Logger logger, String message, Throwable throwable) {
        if (logger == null) {
            fallback(Level.WARNING, message, throwable);
            return;
        }
        logger.warn(format(logger, message), throwable);
    }

    public static void relay(org.slf4j.Logger logger, Level level, String message, Throwable throwable) {
        if (logger == null) {
            fallback(level, message, throwable);
            return;
        }
        String formatted = format(logger, message);
        if (level.intValue() >= Level.SEVERE.intValue()) {
            logger.error(formatted, throwable);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            logger.warn(formatted, throwable);
        } else if (level.intValue() >= Level.INFO.intValue()) {
            logger.info(formatted, throwable);
        } else {
            logger.debug(formatted, throwable);
        }
    }

    private static String format(Logger logger, String message) {
        if (message != null && message.startsWith(PREFIX)) {
            return message;
        }
        if (logger != null && hasPluginName(logger.getName())) {
            return message;
        }
        return PREFIX + message;
    }

    private static String format(org.slf4j.Logger logger, String message) {
        if (message != null && message.startsWith(PREFIX)) {
            return message;
        }
        if (logger != null && hasPluginName(logger.getName())) {
            return message;
        }
        return PREFIX + message;
    }

    private static boolean hasPluginName(String loggerName) {
        return loggerName != null && loggerName.toLowerCase(Locale.ROOT).contains("proxyidentity");
    }

    private static void fallback(Level level, String message, Throwable throwable) {
        String formatted = PREFIX + message;
        if (level.intValue() >= Level.WARNING.intValue()) {
            System.err.println(formatted);
            if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        } else {
            System.out.println(formatted);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }
}
