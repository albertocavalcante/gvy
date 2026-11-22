// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.ConsoleAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingProviderTest {
    private companion object {
        const val MAX_CAPTURE_BYTES = 8192
    }

    @Test
    fun `test logging uses the Logback provider`() {
        assertEquals("ch.qos.logback.classic.LoggerContext", LoggerFactory.getILoggerFactory().javaClass.name)
    }

    @Test
    fun `quiet test policy retains warnings and errors and allows a restored debug override`() {
        val resource = assertNotNull(javaClass.classLoader.getResource("logback-test.xml"))
        val actual = LoggerFactory.getILoggerFactory() as LoggerContext
        val actualConsole = assertQuietPolicy(actual)
        val originalSystemErr = System.err
        val originalConsoleStream = actualConsole.outputStream
        val originalMdcAdapter = assertNotNull(actual.mdcAdapter)

        val isolated = LoggerContext()
        isolated.mdcAdapter = LogbackMDCAdapter()
        assertNotSame(originalMdcAdapter, isolated.mdcAdapter)
        isolated.name = "logging-parity-${UUID.randomUUID()}"
        isolated.start()
        try {
            val configurator = JoranConfigurator()
            configurator.context = isolated
            configurator.doConfigure(resource)
            val console = assertQuietPolicy(isolated)
            assertIs<PatternLayoutEncoder>(console.encoder)
            val logger = isolated.getLogger("com.github.albertocavalcante.gvy.build.LoggingParityProbe")
            val capture = boundedCapture()
            val originalPrivateStream = assertNotNull(console.outputStream)
            try {
                console.outputStream = capture
                assertTrue(logger.isAdditive)
                assertRenderedRecords(logger, capture)
            } finally {
                console.outputStream = originalPrivateStream
            }
        } finally {
            isolated.stop()
        }
        assertSame(originalSystemErr, System.err)
        assertSame(originalConsoleStream, actualConsole.outputStream)
        assertSame(originalMdcAdapter, actual.mdcAdapter)
    }

    private fun boundedCapture(): ByteArrayOutputStream = object : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(size() < MAX_CAPTURE_BYTES) { "Test log capture exceeded its bound" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length <= MAX_CAPTURE_BYTES - size()) { "Test log capture exceeded its bound" }
            super.write(bytes, offset, length)
        }
    }

    private fun assertRenderedRecords(logger: Logger, capture: ByteArrayOutputStream) {
        logger.level = null
        assertFalse(logger.isInfoEnabled)
        assertFalse(logger.isDebugEnabled)
        logger.info("logging-parity-unexpected-info")
        logger.debug("logging-parity-unexpected-debug")
        logger.warn("logging-parity-warn")
        logger.error("logging-parity-error")
        logger.level = Level.DEBUG
        assertTrue(logger.isDebugEnabled)
        logger.debug("logging-parity-debug")
        val records = capture.toString(Charsets.UTF_8.name()).lineSequence()
            .filter { it.contains("logging-parity-") }
            .toList()
        val expected = listOf(
            Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} WARN\s+\S+ - logging-parity-warn$"""),
            Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} ERROR\s+\S+ - logging-parity-error$"""),
            Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} DEBUG\s+\S+ - logging-parity-debug$"""),
        )
        assertEquals(expected.size, records.size)
        expected.zip(records).forEach { (pattern, record) ->
            assertTrue(pattern.matches(record), "Unexpected rendered log record: $record")
        }
    }

    private fun assertQuietPolicy(context: LoggerContext): ConsoleAppender<*> {
        assertEquals("quiet-v1", context.getProperty("gvy.test.logging.policy"))
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        assertEquals(Level.WARN, root.level)
        val appenders = root.iteratorForAppenders().asSequence().toList()
        assertEquals(1, appenders.size)
        val console = assertIs<ConsoleAppender<*>>(appenders.single())
        assertTrue(console.isStarted)
        assertEquals("STDERR", console.name)
        assertEquals("System.err", console.target)
        return console
    }
}
