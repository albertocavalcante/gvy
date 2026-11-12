// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.openrewrite.Cursor
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.SourceFile
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.internal.RecipeRunException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenRewritePipelineFailureTest {
    @TestFactory
    fun `each engine phase preserves failures instead of unchanged text`(): List<DynamicTest> =
        Phase.entries.flatMap { phase ->
            listOf(
                IOException("$phase failed"),
                CancellationException("$phase cancelled"),
                LinkageError("$phase fatal"),
            )
                .map { failure ->
                    dynamicTest("$phase ${failure.javaClass.simpleName}") {
                        assertThatThrownBy { formatter(failingOperations(phase, failure)).formatChecked(SOURCE) }
                            .isSameAs(failure)
                    }
                }
        }

    @TestFactory
    fun `known engine wrappers cannot hide a phase failure`(): List<DynamicTest> = Phase.entries.flatMap { phase ->
        listOf(IOException("$phase failed"), CancellationException("$phase cancelled"), LinkageError("$phase fatal"))
            .map { failure ->
                dynamicTest("$phase ${failure.javaClass.simpleName}") {
                    val wrapped = RecipeRunException(failure, Cursor(null, "failure-control"))

                    assertThatThrownBy { formatter(failingOperations(phase, wrapped)).formatChecked(SOURCE) }
                        .isSameAs(failure)
                }
            }
    }

    @TestFactory
    fun `reported recipe failures cannot become successful no ops`(): List<DynamicTest> = listOf(
        IOException("recipe reported an error"),
        CancellationException("recipe reported cancellation"),
        LinkageError("recipe reported fatal error"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val operations = object : RewriteOperations() {
                override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? {
                    context.onError.accept(failure)
                    return null
                }
            }

            assertThatThrownBy { formatter(operations).formatChecked(SOURCE) }.isSameAs(failure)
        }
    }

    @TestFactory
    fun `real recipe engine failures preserve ordinary cancellation and fatal outcomes`(): List<DynamicTest> = listOf(
        IOException("real recipe failed"),
        CancellationException("real recipe cancelled"),
        LinkageError("real recipe fatal"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val recipe = object : Recipe() {
                override fun getDisplayName(): String = "Failing formatting recipe"
                override fun getDescription(): String = "Exercises the real recipe engine failure boundary."
                override fun getVisitor(): TreeVisitor<*, ExecutionContext> =
                    object : TreeVisitor<Tree, ExecutionContext>() {
                        override fun preVisit(tree: Tree, context: ExecutionContext): Tree = throw failure
                    }
            }
            val operations = object : RewriteOperations() {
                override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? =
                    recipe.run(InMemoryLargeSourceSet(sources), context).changeset.allResults
                        .mapNotNull { it.after }.firstOrNull()
            }

            assertThatThrownBy { formatter(operations).formatChecked(SOURCE) }.isSameAs(failure)
        }
    }

    @Test
    fun `a failed operation cannot contaminate the following healthy operation`() {
        val calls = AtomicInteger()
        val failure = IOException("first operation failed")
        val operations = object : RewriteOperations() {
            override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? {
                if (calls.getAndIncrement() == 0) context.onError.accept(failure)
                return null
            }
        }
        val formatter = formatter(operations)

        assertThatThrownBy { formatter.formatChecked(SOURCE) }.isSameAs(failure)
        assertThat(formatter.formatChecked(SOURCE)).isEqualTo(FormatOutcome.Unchanged(SOURCE))
    }

    @Test
    fun `a reported failure is isolated while another operation is still running`() {
        val failed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("concurrent operation failed")
        val operations = object : RewriteOperations() {
            override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? {
                if (sources.single().printAll().contains("bad")) {
                    context.onError.accept(failure)
                    failed.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
                }
                return null
            }
        }
        val formatter = formatter(operations)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val bad = worker.submit<Any> { formatter.formatChecked("def bad = 1\n") }
            try {
                assertThat(failed.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                assertThat(formatter.formatChecked(SOURCE)).isEqualTo(FormatOutcome.Unchanged(SOURCE))
            } finally {
                release.countDown()
            }
            assertThatThrownBy { bad.get(WAIT_SECONDS, TimeUnit.SECONDS) }.hasCause(failure)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertThat(worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `real invalid syntax is refused without altering its source`() {
        val source = "def x = "

        val outcome = OpenRewriteFormatter().formatChecked(source)

        assertThat(outcome).isInstanceOf(FormatOutcome.Refused::class.java)
        assertThat(outcome.text).isEqualTo(source)
    }

    @TestFactory
    fun `syntax failures cannot hide suppressed cleanup failures`(): List<DynamicTest> = listOf(
        IOException("parser cleanup failed"),
        CancellationException("parser cleanup cancelled"),
        LinkageError("parser cleanup fatal"),
    ).map { cleanup ->
        dynamicTest(cleanup.javaClass.simpleName) {
            val operations = object : RewriteOperations() {
                override fun parse(text: String, context: ExecutionContext): List<SourceFile> = super.parse(
                    "def x = ",
                    InMemoryExecutionContext { sourceFailure ->
                        // GroovyParser also suppresses classloader-close failures onto a syntax failure.
                        sourceFailure.addSuppressed(cleanup)
                        context.onError.accept(sourceFailure)
                    },
                )
            }

            assertThatThrownBy { formatter(operations).formatChecked(SOURCE) }.isSameAs(cleanup)
        }
    }

    @Test
    fun `a parser roundtrip mismatch is refused before any recipe runs`() {
        val operations = object : RewriteOperations() {
            override fun parse(text: String, context: ExecutionContext): List<SourceFile> =
                super.parse(text.replace("healthy", "altered"), context)

            override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? =
                error("a mismatched parse must not reach the recipe")
        }

        val outcome = formatter(operations).formatChecked(SOURCE)

        assertThat(outcome).isInstanceOf(FormatOutcome.Refused::class.java)
        assertThat(outcome.text).isEqualTo(SOURCE)
    }

    @TestFactory
    fun `an engine error alongside a syntax parse marker is still an engine failure`(): List<DynamicTest> = listOf(
        IOException("parser worker failed"),
        UnsupportedOperationException("parser service unavailable"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val operations = object : RewriteOperations() {
                override fun parse(text: String, context: ExecutionContext): List<SourceFile> {
                    val parsed = super.parse("def x = ", context)
                    context.onError.accept(failure)
                    return parsed
                }
            }

            assertThatThrownBy { formatter(operations).formatChecked(SOURCE) }.isSameAs(failure)
        }
    }

    @TestFactory
    fun `critical callbacks win over syntax markers and roundtrip refusals`(): List<DynamicTest> =
        listOf(false, true).flatMap { syntaxError ->
            listOf(CancellationException("cancelled"), LinkageError("fatal"), AssertionError("engine assertion"))
                .map { failure ->
                    dynamicTest("syntaxError=$syntaxError ${failure.javaClass.simpleName}") {
                        val operations = object : RewriteOperations() {
                            override fun parse(text: String, context: ExecutionContext): List<SourceFile> {
                                val source = if (syntaxError) "def x = " else text.replace("healthy", "altered")
                                val parsed = super.parse(source, context)
                                // Model an engine that catches an error callback but still returns a parse result.
                                runCatching { context.onError.accept(failure) }
                                return parsed
                            }
                        }

                        assertThatThrownBy { formatter(operations).formatChecked(SOURCE) }.isSameAs(failure)
                    }
                }
        }

    private fun failingOperations(phase: Phase, failure: Throwable): RewriteOperations = object : RewriteOperations() {
        override fun parse(text: String, context: ExecutionContext): List<SourceFile> {
            if (phase == Phase.PARSE) throw failure
            return super.parse(text, context)
        }

        override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? {
            if (phase == Phase.RECIPE) throw failure
            return sources.single()
        }

        override fun print(source: SourceFile): String {
            if (phase == Phase.PRINT) throw failure
            return super.print(source)
        }
    }

    private fun formatter(operations: RewriteOperations) = OpenRewriteFormatter(TokenStreamGuard::check, operations)

    private enum class Phase { PARSE, RECIPE, PRINT }

    private companion object {
        const val SOURCE = "def healthy = 1\n"
        const val WAIT_SECONDS = 5L
    }
}
