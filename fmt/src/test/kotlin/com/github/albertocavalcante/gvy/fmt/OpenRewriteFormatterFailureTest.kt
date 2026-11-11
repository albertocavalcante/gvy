package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.util.concurrent.CancellationException

class OpenRewriteFormatterFailureTest {
    @TestFactory
    fun `checker failures preserve their original outcome`(): List<DynamicTest> = listOf(
        CancellationException("checker cancelled"),
        LinkageError("checker unavailable"),
        IOException("checker failed"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val formatter = OpenRewriteFormatter { _, _ -> throw failure }

            assertThatThrownBy { formatter.formatChecked("def x=1\n") }.isSameAs(failure)
        }
    }

    @TestFactory
    fun `execution context does not consume cancellation or fatal failure`(): List<DynamicTest> = listOf(
        CancellationException("recipe cancelled"),
        LinkageError("recipe unavailable"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            assertThatThrownBy { RewriteOperation().context.onError.accept(failure) }
                .isSameAs(failure)
        }
    }

    @Test
    fun `healthy blank input remains unchanged`() {
        assertThat(OpenRewriteFormatter().formatChecked("\n")).isEqualTo(FormatOutcome.Unchanged("\n"))
    }

    @Test
    fun `an unsafe token change remains an intentional refusal`() {
        val source = "def x=1\n"
        val formatter = OpenRewriteFormatter { original, _ -> TokenStreamGuard.check(original, "def other=1\n") }

        val outcome = formatter.formatChecked(source)

        assertThat(outcome).isInstanceOf(FormatOutcome.Refused::class.java)
        assertThat(outcome.text).isEqualTo(source)
        assertThat((outcome as FormatOutcome.Refused).cause).isNull()
    }
}
