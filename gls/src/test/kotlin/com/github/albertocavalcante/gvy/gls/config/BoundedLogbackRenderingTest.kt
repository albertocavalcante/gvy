// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.ConsoleAppender
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `hostile metadata cannot spoof severity or split the logger token`() = withEncoder { encoder ->
        val input = event("actual failure", emptyArray(), thread = "t] WARN spoof").apply {
            loggerName = "hostile logger"
        }

        val output = encoder.encode(input).toString(Charsets.UTF_8)

        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[.*] ERROR \\S+ -- actual failure\\n$").matches(output))
        assertTrue(!output.contains("] WARN "))
        assertTrue(output.contains("hostile\\u0020logger"))
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
    fun `unconsumed escaped placeholder tails remain literal`() = withEncoder { encoder ->
        val tail = """literal \{} and \\{}"""
        val withoutArguments = listOf<Array<Any>?>(null, emptyArray())

        for (arguments in withoutArguments) {
            val output = encoder.encode(event(tail, arguments)).toString(Charsets.UTF_8)
            assertTrue(output.endsWith(" -- $tail\n"))
        }

        val output = encoder.encode(event("{} $tail", arrayOf("value"))).toString(Charsets.UTF_8)
        assertTrue(output.endsWith(" -- value $tail\n"))
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
    fun `oversized escaped message retains root and cause identity`() = withEncoder { encoder ->
        val failure = IllegalStateException("root failure", IllegalArgumentException("cause failure"))
        val input = event("{}", arrayOf("\u0000".repeat(100_000))).apply {
            setThrowableProxy(ThrowableProxy(failure))
        }

        val bytes = encoder.encode(input)
        val output = bytes.toString(Charsets.UTF_8)

        assertTrue(bytes.size <= 65_536)
        assertTrue(output.contains("java.lang.IllegalStateException"))
        assertTrue(output.contains("java.lang.IllegalArgumentException"))
        assertTrue(output.contains("[truncated]"))
    }

    @Test
    fun `oversized root frames cannot hide cause identity`() = withEncoder { encoder ->
        val failure = IllegalStateException("root failure", IllegalArgumentException("cause failure")).apply {
            stackTrace = Array(128) {
                StackTraceElement("X".repeat(2_000), "method", "Example.groovy", it)
            }
        }
        val input = event("failure", emptyArray()).apply { setThrowableProxy(ThrowableProxy(failure)) }

        val bytes = encoder.encode(input)
        val output = bytes.toString(Charsets.UTF_8)

        assertTrue(bytes.size <= 65_536)
        assertTrue(output.contains("java.lang.IllegalStateException"))
        assertTrue(output.contains("java.lang.IllegalArgumentException"))
        assertTrue(output.contains("[truncated]"))
    }

    @Test
    fun `native frames retain the native method label`() = withEncoder { encoder ->
        val failure = IllegalStateException("native failure").apply {
            stackTrace = arrayOf(StackTraceElement("example.Native", "invoke", null, -2))
        }
        val input = event("failure", emptyArray()).apply { setThrowableProxy(ThrowableProxy(failure)) }

        val output = encoder.encode(input).toString(Charsets.UTF_8)

        assertTrue(output.contains("example.Native.invoke(Native Method)"))
    }

    @Test
    fun `configured warn and debug throwable lines remain continuations`() = withEncoder { encoder ->
        val records = listOf(Level.DEBUG, Level.WARN).flatMap { severity ->
            val failure = OddSignal().apply {
                stackTrace = arrayOf(StackTraceElement("example.Signal", "run", "Signal.groovy", 7))
            }
            val input = event("signal", emptyArray(), severity = severity).apply {
                setThrowableProxy(ThrowableProxy(failure))
            }
            val text = encoder.encode(input).toString(Charsets.UTF_8)
            val lines = text.trimEnd('\n').split('\n')
            assertTrue(lines.first().contains(" ${severity.levelStr} "))
            assertTrue(lines.drop(1).all { it.startsWith(" ") || it.startsWith("\t") })
            assertTrue(text.contains("OddSignal"))
            listOf(text, truncatedThrowableRecord(encoder, severity, 3_516))
        }
        System.getenv("GVY_ROUTING_FIXTURE")?.let {
            java.nio.file.Files.writeString(java.nio.file.Path.of(it), records.joinToString(""))
        }
    }

    @Test
    fun `truncation markers retain throwable continuation severity`() = withEncoder { encoder ->
        for (severity in listOf(Level.DEBUG, Level.WARN)) {
            for (padding in 3_515..3_517) {
                val output = truncatedThrowableRecord(encoder, severity, padding)
                val lines = output.trimEnd('\n').split('\n')
                assertTrue(output.toByteArray(Charsets.UTF_8).size <= 65_536)
                assertTrue(output.contains("[truncated]"))
                assertTrue(lines.first().contains(" ${severity.levelStr} "))
                assertTrue(
                    lines.drop(1).all { it.startsWith(" ") || it.startsWith("\t") },
                    "truncation marker must remain a continuation",
                )
            }
        }
    }

    private fun truncatedThrowableRecord(
        encoder: ch.qos.logback.core.encoder.Encoder<ILoggingEvent>,
        severity: Level,
        padding: Int,
    ): String {
        val failure = IllegalStateException("large stack").apply {
            stackTrace = Array(128) { StackTraceElement("X".repeat(1_000), "method", "Example.groovy", it) }
        }
        val input = event("p".repeat(padding), emptyArray(), severity = severity).apply {
            setThrowableProxy(ThrowableProxy(failure))
        }
        return encoder.encode(input).toString(Charsets.UTF_8)
    }

    private class OddSignal : Throwable("signal detail")

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


    @Test
    fun `double escaped placeholder retains slash and substitutes argument`() = withEncoder { encoder ->
        val output = encoder.encode(event("value \\\\{}", arrayOf("replacement"))).toString(Charsets.UTF_8)

        assertTrue(output.contains("value \\replacement"))
    }

    @Test
    fun `configured appender writes one bounded stderr record and flushes`() {
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(assertNotNull(javaClass.classLoader.getResource("logback.xml")))
            }
            val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            val appenders = root.iteratorForAppenders().asSequence().toList()
            assertEquals(1, appenders.size)
            val appender = assertIs<ConsoleAppender<ILoggingEvent>>(appenders.single())
            assertEquals("System.err", appender.target)
            val sink = CountingStream()
            appender.setOutputStream(sink)

            appender.doAppend(event("message {}", arrayOf("x".repeat(100_000))))

            assertEquals(1, sink.writes)
            assertEquals(1, sink.flushes)
            assertTrue(sink.bytes.size() <= 65_536)
            assertTrue(sink.bytes.toString(Charsets.UTF_8).contains(" ERROR "))
            assertTrue(sink.bytes.toString(Charsets.UTF_8).endsWith('\n'))
        } finally {
            context.stop()
        }
    }

    @Test
    fun `interrupted slow appender does not leave the writing thread blocked`() {
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writer: Thread? = null
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(assertNotNull(javaClass.classLoader.getResource("logback.xml")))
            }
            val appender = assertIs<OutputStreamAppender<ILoggingEvent>>(
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("STDERR"),
            )
            appender.setOutputStream(object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    entered.countDown()
                    try {
                        release.await()
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted", failure)
                    }
                }
            })
            writer = Thread { appender.doAppend(event("slow sink", emptyArray())) }
            writer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.interrupt()
            writer.join(5_000)
            assertTrue(!writer.isAlive, "interrupted sink must not retain the appender thread")
        } finally {
            release.countDown()
            writer?.join(5_000)
            context.stop()
        }
    }


    @Test
    fun `custom character sequence and trailing throwable are not stringified`() = withEncoder { encoder ->
        var calls = 0
        val custom = object : CharSequence {
            override val length: Int get() = error("unexpected length")
            override fun get(index: Int): Char = error("unexpected character")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("unexpected slice")
            override fun toString(): String {
                calls++
                return "unsafe"
            }
        }
        val failure = object : IllegalStateException("safe cause") {
            override fun toString(): String {
                calls++
                return "unsafe throwable"
            }
        }
        val input = event("value {}", arrayOf(custom, failure)).apply {
            setThrowableProxy(ThrowableProxy(failure))
        }

        // Logback constructs the proxy before the encoder boundary.
        val producerCalls = calls
        val output = encoder.encode(input).toString(Charsets.UTF_8)

        assertEquals(producerCalls, calls, "Encoding must not invoke argument or throwable toString")
        assertTrue(output.contains("safe cause"))
        assertTrue(!output.contains("unsafe"))
    }

    @Test
    fun `large supplementary stack frames stay valid inside total byte cap`() = withEncoder { encoder ->
        val failure = IllegalStateException("large stack").apply {
            stackTrace = Array(128) {
                StackTraceElement("🙂".repeat(2_000), "method", "Example.groovy", it)
            }
        }
        val input = event("failure", emptyArray()).apply { setThrowableProxy(ThrowableProxy(failure)) }

        val bytes = encoder.encode(input)
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()

        assertTrue(bytes.size <= 65_536)
        assertTrue(decoded.contains("[truncated]"))
        assertTrue(decoded.endsWith('\n'))
    }

    @Test
    fun `configured synchronous appender never formats arbitrary arguments`() {
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        var calls = 0
        val argument = object {
            override fun toString(): String {
                calls++
                return "unsafe argument"
            }
        }
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(assertNotNull(javaClass.classLoader.getResource("logback.xml")))
            }
            val appender = assertIs<BoundedConsoleAppender>(
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("STDERR"),
            )
            val sink = CountingStream()
            appender.setOutputStream(sink)
            context.getLogger("com.github.albertocavalcante.gvy.test").error("value {}", argument)
            assertEquals(0, calls)
            assertEquals(1, sink.writes)
            assertEquals(1, sink.flushes)
            assertTrue(sink.bytes.toString(Charsets.UTF_8).contains("<object>"))
        } finally {
            context.stop()
        }
    }

    private class CountingStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        var flushes = 0

        override fun write(value: Int) {
            writes++
            bytes.write(value)
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            writes++
            bytes.write(data, offset, length)
        }

        override fun flush() {
            flushes++
        }
    }

    private fun event(
        message: String,
        arguments: Array<Any>?,
        thread: String = "main",
        severity: Level = Level.ERROR,
    ): LoggingEvent =
        LoggingEvent().apply {
            loggerName = "com.github.albertocavalcante.gvy.test"
            threadName = thread
            level = severity
            timeStamp = 1_700_000_000_000
            this.message = message
            argumentArray = arguments
        }

    private fun withEncoder(block: (ch.qos.logback.core.encoder.Encoder<ILoggingEvent>) -> Unit) {
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
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
