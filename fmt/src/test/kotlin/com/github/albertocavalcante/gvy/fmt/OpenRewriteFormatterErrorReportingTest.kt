package com.github.albertocavalcante.gvy.fmt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.openrewrite.ExecutionContext
import org.openrewrite.SourceFile
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@Isolated("Captures the process error stream and formatter log events")
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
            val operations = object : RewriteOperations() {
                override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? {
                    context.onError.accept(failure)
                    return null
                }
            }
            val formatter = OpenRewriteFormatter(TokenStreamGuard::check, operations)
            assertThatThrownBy { formatter.formatChecked("def x = 1\n") }.isSameAs(failure)
        } finally {
            System.setErr(originalErr)
        }

        assertThat(stderr.toString(Charsets.UTF_8)).isEmpty()
        val event = logs.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat((event.throwableProxy as ThrowableProxy).throwable).isSameAs(failure)
    }

    /**
     * A formatter whose guard sees the damage from #1523 (`x as BigDecimal` printed as
     * `xasBigDecimal`), so these tests do not depend on the formatter still having that bug.
     */
    private fun formatterSeeingAsCastDamage() = OpenRewriteFormatter { original, _ ->
        TokenStreamGuard.check(original, original.replace("x as BigDecimal", "xasBigDecimal"))
    }

    // `println  y` gives the formatter something to change, so the result reaches the guard.
    private val damagedInput = "def y = x as BigDecimal\nprintln  y\n"

    @Test
    fun `a refused formatting result is logged at warn with the first token difference`() {
        val input = damagedInput

        val result = formatterSeeingAsCastDamage().format(input)

        assertThat(result).isEqualTo(input)
        val event = logs.list.single { it.level == Level.WARN }
        assertThat(event.formattedMessage)
            .contains("Refusing formatting result")
            .contains("'x' at 1:9")
            .contains("'xasBigDecimal' at 1:9")
    }

    @Test
    fun `a token stream check failure remains an engine error rather than an intentional refusal`() {
        val failure = IllegalStateException("lexer exploded")
        val formatter = OpenRewriteFormatter { _, _ -> throw failure }
        val input = "def x=1\n"

        assertThatThrownBy { formatter.formatChecked(input) }.isSameAs(failure)
        assertThatThrownBy { formatter.format(input) }.isSameAs(failure)
        assertThat(logs.list).noneMatch { it.level == Level.WARN }
    }

    @Test
    fun `formatChecked reports refusals without logging them`() {
        val outcome = formatterSeeingAsCastDamage().formatChecked(damagedInput)

        assertThat(outcome).isInstanceOf(FormatOutcome.Refused::class.java)
        assertThat((outcome as FormatOutcome.Refused).reason).contains("'xasBigDecimal' at 1:9")
        assertThat(logs.list).noneMatch { it.level == Level.WARN }
    }
}
