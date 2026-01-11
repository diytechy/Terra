package com.dfsek.terra.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility class to configure logging settings for Terra and its dependencies.
 * Call {@link #configure()} early in platform initialization.
 */
public final class LoggingConfig {
    private static final Logger logger = LoggerFactory.getLogger(LoggingConfig.class);
    private static volatile boolean configured = false;

    private LoggingConfig() {
    }

    /**
     * Configures logging settings, including suppressing known nuisance errors
     * from dependencies.
     */
    public static synchronized void configure() {
        if (configured) {
            return;
        }
        configured = true;

        // Suppress Seismic ReflectionUtils error about theUnsafe field
        // This is a known issue where Seismic uses getField() instead of getDeclaredField()
        // for a private field. The functionality still works via fallback.
        suppressSeismicReflectionError();
    }

    private static void suppressSeismicReflectionError() {
        try {
            // Try Logback first
            if (tryLogback("com.dfsek.seismic.util.ReflectionUtils")) {
                logger.debug("Configured Logback to suppress Seismic ReflectionUtils errors");
                return;
            }

            // Try Log4j2
            if (tryLog4j2("com.dfsek.seismic.util.ReflectionUtils")) {
                logger.debug("Configured Log4j2 to suppress Seismic ReflectionUtils errors");
                return;
            }

            // Try java.util.logging (JUL)
            if (tryJUL("com.dfsek.seismic.util.ReflectionUtils")) {
                logger.debug("Configured JUL to suppress Seismic ReflectionUtils errors");
                return;
            }

            logger.debug("Could not programmatically suppress Seismic ReflectionUtils errors - unknown logging backend");
        } catch (Exception e) {
            logger.debug("Failed to configure logging suppression", e);
        }
    }

    private static boolean tryLogback(String loggerName) {
        try {
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");

            Object logbackLogger = LoggerFactory.getLogger(loggerName);
            if (loggerClass.isInstance(logbackLogger)) {
                Object offLevel = levelClass.getField("OFF").get(null);
                loggerClass.getMethod("setLevel", levelClass).invoke(logbackLogger, offLevel);
                return true;
            }
        } catch (ClassNotFoundException e) {
            // Logback not present
        } catch (Exception e) {
            logger.debug("Failed to configure Logback", e);
        }
        return false;
    }

    private static boolean tryLog4j2(String loggerName) {
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");

            Object offLevel = levelClass.getField("OFF").get(null);
            configuratorClass.getMethod("setLevel", String.class, levelClass)
                    .invoke(null, loggerName, offLevel);
            return true;
        } catch (ClassNotFoundException e) {
            // Log4j2 not present
        } catch (Exception e) {
            logger.debug("Failed to configure Log4j2", e);
        }
        return false;
    }

    private static boolean tryJUL(String loggerName) {
        try {
            java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(loggerName);
            julLogger.setLevel(java.util.logging.Level.OFF);
            return true;
        } catch (Exception e) {
            logger.debug("Failed to configure JUL", e);
        }
        return false;
    }
}
