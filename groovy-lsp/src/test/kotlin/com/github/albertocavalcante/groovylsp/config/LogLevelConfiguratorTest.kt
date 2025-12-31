package com.github.albertocavalcante.groovylsp.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory

class LogLevelConfiguratorTest {

    @ParameterizedTest
    @CsvSource(
        "error, ERROR",
        "warn, WARN",
        "info, INFO",
        "debug, DEBUG",
        "trace, TRACE",
        "ERROR, ERROR",
        "DEBUG, DEBUG",
        "Invalid, INFO",
        ", INFO",
    )
    fun `LogLevel fromString parses correctly`(input: String?, expected: String) {
        val logLevel = ServerConfiguration.LogLevel.fromString(input)
        assertThat(logLevel.name).isEqualTo(expected)
    }

    @Test
    fun `apply changes Logback root logger level`() {
        // Apply DEBUG level
        LogLevelConfigurator.apply(ServerConfiguration.LogLevel.DEBUG)

        // Check the Logback root logger was updated
        val loggerFactory = LoggerFactory.getILoggerFactory()
        if (loggerFactory is LoggerContext) {
            val rootLogger = loggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
            assertThat(rootLogger.level).isEqualTo(Level.DEBUG)
        }
    }

    @Test
    fun `apply changes Groovy LSP specific logger level`() {
        LogLevelConfigurator.apply(ServerConfiguration.LogLevel.TRACE)

        val loggerFactory = LoggerFactory.getILoggerFactory()
        if (loggerFactory is LoggerContext) {
            val groovyLogger = loggerFactory.getLogger("com.github.albertocavalcante.groovylsp")
            assertThat(groovyLogger.level).isEqualTo(Level.TRACE)
        }
    }

    @Test
    fun `ServerConfiguration parses logLevel from map`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.server.logLevel" to "debug",
            ),
        )

        assertThat(config.logLevel).isEqualTo(ServerConfiguration.LogLevel.DEBUG)
    }

    @Test
    fun `ServerConfiguration defaults logLevel to INFO when not specified`() {
        val config = ServerConfiguration.fromMap(emptyMap())

        assertThat(config.logLevel).isEqualTo(ServerConfiguration.LogLevel.INFO)
    }

    @Test
    fun `ServerConfiguration handles invalid logLevel gracefully`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.server.logLevel" to "invalid_level",
            ),
        )

        // Should default to INFO for invalid values
        assertThat(config.logLevel).isEqualTo(ServerConfiguration.LogLevel.INFO)
    }
}
