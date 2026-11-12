// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.apache.groovy.parser.antlr4.GroovySyntaxError
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
    fun `critical cleanup wins over earlier ordinary cleanup`() {
        val cleanup = CancellationException("cleanup cancelled")
        val sourceFailure = syntaxFailure().apply {
            addSuppressed(IOException("earlier cleanup failure"))
            addSuppressed(cleanup)
        }

        assertThatThrownBy { RewriteOperation().context.onError.accept(sourceFailure) }.isSameAs(cleanup)
    }

    @Test
    fun `nested cleanup evidence survives a cycle of expected source failures`() {
        val sourceFailure = syntaxFailure()
        val nested = syntaxFailure().apply { addSuppressed(sourceFailure) }
        val evidence = IOException("independent cleanup evidence")
        val cleanup = IOException("cleanup failed").apply { addSuppressed(evidence) }
        sourceFailure.addSuppressed(nested)
        nested.addSuppressed(cleanup)
        val operation = RewriteOperation()

        operation.context.onError.accept(sourceFailure)

        assertThatThrownBy { operation.throwIfFailed(allowSourceErrors = true) }.isSameAs(cleanup)
        assertThat(cleanup.suppressed).containsExactly(evidence)
    }

    @Test
    fun `a modest graph of source failures remains an expected source refusal`() {
        val sourceFailure = syntaxFailure().apply {
            repeat(MODEST_SOURCE_FAILURE_COUNT) { addSuppressed(syntaxFailure()) }
        }
        val operation = RewriteOperation()

        operation.context.onError.accept(sourceFailure)
        operation.throwIfFailed(allowSourceErrors = true)
    }

    @Test
    fun `an excessive failure graph fails closed without retaining the oversized graph`() {
        val sourceFailure = syntaxFailure().apply {
            repeat(EXCESSIVE_SOURCE_FAILURE_COUNT) { addSuppressed(syntaxFailure()) }
        }
        val operation = RewriteOperation()

        operation.context.onError.accept(sourceFailure)

        assertThatThrownBy { operation.throwIfFailed(allowSourceErrors = true) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("evidence limit")
            .hasNoCause()
    }

    @Test
    fun `an observed critical cleanup still wins when the remaining graph exceeds the bound`() {
        val cleanup = LinkageError("cleanup fatal")
        val sourceFailure = syntaxFailure().apply {
            addSuppressed(cleanup)
            repeat(EXCESSIVE_SOURCE_FAILURE_COUNT) { addSuppressed(syntaxFailure()) }
        }

        assertThatThrownBy { RewriteOperation().context.onError.accept(sourceFailure) }.isSameAs(cleanup)
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

    private fun syntaxFailure() = GroovySyntaxError("source syntax error", GroovySyntaxError.PARSER, 1, 1)

    private companion object {
        const val MODEST_SOURCE_FAILURE_COUNT = 16
        const val EXCESSIVE_SOURCE_FAILURE_COUNT = 128
    }
}
