package com.github.albertocavalcante.gvy.fmt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class OpenRewriteFormatterErrorReportingTest {

    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = LoggerFactory.getLogger(OpenRewriteFormatter::class.java) as Logger

    @BeforeEach
    fun attach() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
    }

    @Test
    fun `recipe errors are logged at error level instead of printed to stderr`() {
        val failure = IllegalStateException("recipe failed")
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            OpenRewriteFormatter().executionContext.onError.accept(failure)
        } finally {
            System.setErr(originalErr)
        }

        assertThat(stderr.toString(Charsets.UTF_8)).isEmpty()
        val event = logs.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat((event.throwableProxy as ThrowableProxy).throwable).isSameAs(failure)
    }

    @Test
    fun `a refused formatting result is logged at warn with the first token difference`() {
        val input = "def y = x as BigDecimal\n"

        val result = OpenRewriteFormatter().format(input)

        assertThat(result).isEqualTo(input)
        val event = logs.list.single { it.level == Level.WARN }
        assertThat(event.formattedMessage)
            .contains("Refusing formatting result")
            .contains("'x' at 1:9")
            .contains("'xasBigDecimal' at 1:9")
    }
}
