// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.OutputStreamAppender
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoundedLogbackRenderingTest {
    @Test
    fun `configured stderr encoder bounds the complete message and throwable`() = withEncoder { encoder ->
        val event = event("request {}", arrayOf("x".repeat(100_000))).apply {
            setThrowableProxy(ThrowableProxy(IllegalStateException("y".repeat(100_000))))
        }

        val bytes = encoder.encode(event)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(bytes.size <= 65_536, "message and stack trace must share the record budget: ${bytes.size}")
        assertTrue(text.endsWith('\n'))
        assertTrue(text.contains("[truncated]"))
        assertTrue(text.contains("ERROR"))
    }

    @Test
    fun `stderr record preserves the routing prefix and escapes untrusted controls`() = withEncoder { encoder ->
        val output = encoder.encode(event("first\nsecond", emptyArray())).toString(Charsets.UTF_8)

        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[main] ERROR \\S+ -- ").containsMatchIn(output))
        assertTrue(!output.contains("first\nsecond"))
        assertTrue(output.endsWith('\n'))
    }

    @Test
    fun `unbounded thread metadata cannot exhaust the record or hide its severity`() = withEncoder { encoder ->
        val bytes = encoder.encode(event("failure", emptyArray(), thread = "t".repeat(100_000)))
        val output = bytes.toString(Charsets.UTF_8)
        assertTrue(bytes.size <= 65_536)
        assertTrue(output.contains(" ERROR "))
        assertTrue(output.endsWith('\n'))
    }

    @Test
    fun `utf8 truncation never splits a supplementary character`() = withEncoder { encoder ->
        val bytes = encoder.encode(event("{}", arrayOf("🙂".repeat(40_000))))
        val output = bytes.toString(Charsets.UTF_8)
        assertTrue(bytes.size <= 65_536)
        assertTrue(!output.contains('\uFFFD'))
        assertTrue(output.contains("[truncated]"))
    }

    @Test
    fun `configured stderr encoder never invokes arbitrary argument toString`() = withEncoder { encoder ->
        var calls = 0
        val argument = object {
            override fun toString(): String {
                calls++
                return "argument-toString-was-called"
            }
        }
        val number = object : Number() {
            override fun toByte(): Byte = error("unexpected number conversion")
            override fun toDouble(): Double = error("unexpected number conversion")
            override fun toFloat(): Float = error("unexpected number conversion")
            override fun toInt(): Int = error("unexpected number conversion")
            override fun toLong(): Long = error("unexpected number conversion")
            override fun toShort(): Short = error("unexpected number conversion")
            override fun toString(): String {
                calls++
                return "number-toString-was-called"
            }
        }
        val output = encoder.encode(event("failure {} {}", arrayOf(argument, number))).toString(Charsets.UTF_8)

        assertEquals(0, calls)
        assertTrue(output.contains("failure"))
        assertTrue(!output.contains("argument-toString-was-called"))
        assertTrue(!output.contains("number-toString-was-called"))
    }

    @Test
    fun `escaped placeholders and missing arguments remain legible`() = withEncoder { encoder ->
        val output = encoder.encode(event("escaped \\{} {} {}", arrayOf("replacement"))).toString(Charsets.UTF_8)
        assertTrue(output.contains("escaped {} replacement {}"))
    }

    @Test
    fun `a url split at the input scan boundary never exposes its userinfo`() = withEncoder { encoder ->
        val userinfoPrefix = "https://SECRET"
        val input = "p".repeat(16_384 - userinfoPrefix.length) + userinfoPrefix + "@repo.example/path"

        val output = encoder.encode(event("{}", arrayOf(input))).toString(Charsets.UTF_8)
        assertTrue(!output.contains("SECRET"))
        assertTrue(output.contains("[truncated]"))
    }

    @Test
    fun `cause and suppressed details stay readable without embedding untrusted lines`() = withEncoder { encoder ->
        val cause = IllegalArgumentException("token=OPEN-SECRET\nINJECTED")
        val failure = IllegalStateException("operation failed", cause).apply {
            addSuppressed(UnsupportedOperationException("secondary failure"))
            stackTrace = arrayOf(StackTraceElement("Evil\nFRAME", "run", "Example.groovy", 1))
        }
        val event = event("operation failed", emptyArray()).apply { setThrowableProxy(ThrowableProxy(failure)) }

        val output = encoder.encode(event).toString(Charsets.UTF_8)
        assertTrue(output.contains("IllegalStateException"))
        assertTrue(output.contains("IllegalArgumentException"))
        assertTrue(output.contains("UnsupportedOperationException"))
        assertTrue(output.contains("\n\tat "), "renderer-generated stack lines should stay readable")
        assertTrue(!output.contains("OPEN-SECRET"))
        assertTrue(!output.contains("\nINJECTED"))
        assertTrue(!output.contains("\nFRAME"))
    }

    @Test
    fun `cyclic and oversized throwable graphs produce bounded output`() = withEncoder { encoder ->
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.addSuppressed(second)
        second.addSuppressed(first)
        repeat(100) { first.addSuppressed(IllegalStateException("suppressed-$it")) }
        first.stackTrace = Array(500) { StackTraceElement("example.Class$it", "method", "Example.groovy", it) }
        val event = event("failure", emptyArray()).apply { setThrowableProxy(ThrowableProxy(first)) }

        val bytes = encoder.encode(event)
        val output = bytes.toString(Charsets.UTF_8)
        assertTrue(bytes.size <= 65_536)
        assertTrue(output.contains("IllegalStateException"))
        assertTrue(output.contains("[truncated]"))
        assertTrue(output.endsWith('\n'))
    }

    private fun event(message: String, arguments: Array<Any>, thread: String = "main"): LoggingEvent =
        LoggingEvent().apply {
            loggerName = "com.github.albertocavalcante.gvy.test"
            threadName = thread
            level = Level.ERROR
            timeStamp = 1_700_000_000_000
            this.message = message
            argumentArray = arguments
        }

    private fun withEncoder(block: (ch.qos.logback.core.encoder.Encoder<ILoggingEvent>) -> Unit) {
        val context = LoggerContext()
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(assertNotNull(javaClass.classLoader.getResource("logback.xml")))
            }
            val appender = assertIs<OutputStreamAppender<ILoggingEvent>>(
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("STDERR"),
            )
            block(appender.encoder)
        } finally {
            context.stop()
        }
    }
}
