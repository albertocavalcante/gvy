package com.github.albertocavalcante.gvy.fmt

import groovyjarjarantlr4.v4.runtime.RecognitionException
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Error callbacks belong to one formatting operation, including callbacks from engine worker threads. */
internal class RewriteOperation {
    // Retain bounded evidence rather than every reported failure and stack trace.
    private val firstFailure = AtomicReference<Throwable?>()
    private val firstEngineFailure = AtomicReference<Throwable?>()
    private val criticalFailure = AtomicReference<Throwable?>()

    val context: ExecutionContext = InMemoryExecutionContext { reported ->
        val failure = reported.originalRecipeFailure()
        firstFailure.compareAndSet(null, failure)
        if (failure.isCritical()) {
            criticalFailure.compareAndSet(null, failure)
            throw failure
        }
        if (!failure.isExpectedSourceError()) firstEngineFailure.compareAndSet(null, failure)
    }

    fun throwIfFailed(allowSourceErrors: Boolean = false) {
        val failure = criticalFailure.get() ?: if (allowSourceErrors) firstEngineFailure.get() else firstFailure.get()
        if (failure != null) {
            if (!failure.isCritical()) logger.error("OpenRewrite formatting recipe failed", failure)
            throw failure
        }
    }

    private fun Throwable.isExpectedSourceError(): Boolean = this is GroovySyntaxError ||
        (
            this is CompilationFailedException && (
                cause is RecognitionException ||
                    unit?.errorCollector?.errors.orEmpty().let { errors ->
                        errors.isNotEmpty() && errors.all { it is SyntaxErrorMessage }
                    }
                )
            )

    // Groovy uses this specific AssertionError subtype for ordinary lexer/parser diagnostics.
    private fun Throwable.isCritical(): Boolean =
        this is CancellationException || (this !is Exception && this !is GroovySyntaxError)

    private companion object {
        val logger = LoggerFactory.getLogger(OpenRewriteFormatter::class.java)
    }
}
