package com.github.albertocavalcante.groovylsp.config

import org.slf4j.LoggerFactory

/**
 * Configures Logback log level at runtime based on server configuration.
 */
object LogLevelConfigurator {
    private val logger = LoggerFactory.getLogger(LogLevelConfigurator::class.java)

    /**
     * Applies the configured log level to all Groovy LSP loggers.
     * This allows runtime configuration of log verbosity via client settings.
     */
    fun apply(logLevel: ServerConfiguration.LogLevel) {
        val logbackLevel = when (logLevel) {
            ServerConfiguration.LogLevel.ERROR -> ch.qos.logback.classic.Level.ERROR
            ServerConfiguration.LogLevel.WARN -> ch.qos.logback.classic.Level.WARN
            ServerConfiguration.LogLevel.INFO -> ch.qos.logback.classic.Level.INFO
            ServerConfiguration.LogLevel.DEBUG -> ch.qos.logback.classic.Level.DEBUG
            ServerConfiguration.LogLevel.TRACE -> ch.qos.logback.classic.Level.TRACE
        }

        // Get the Logback LoggerContext
        val loggerFactory = LoggerFactory.getILoggerFactory()
        if (loggerFactory !is ch.qos.logback.classic.LoggerContext) {
            logger.warn("Cannot configure log level: not using Logback (found ${loggerFactory.javaClass.name})")
            return
        }

        // Update root logger
        val rootLogger = loggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        rootLogger.level = logbackLevel

        // Update Groovy LSP specific loggers
        val groovyLspLogger = loggerFactory.getLogger("com.github.albertocavalcante.groovylsp")
        groovyLspLogger.level = logbackLevel

        val namedLogger = loggerFactory.getLogger("GroovyLSP")
        namedLogger.level = logbackLevel

        logger.info("Log level set to: {}", logLevel.name)
    }
}
