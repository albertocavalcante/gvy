// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import groovyjarjarantlr4.v4.runtime.RecognitionException
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Error callbacks belong to one formatting operation, including callbacks from engine worker threads. */
internal class RewriteOperation {
    // Retain bounded evidence rather than every reported failure and stack trace.
    private val firstFailure = SharedValue<Throwable?>(null)
    private val firstEngineFailure = SharedValue<Throwable?>(null)
    private val criticalFailure = SharedValue<Throwable?>(null)

    val context: ExecutionContext = InMemoryExecutionContext(::inspectFailureEvidence)

    private fun inspectFailureEvidence(reported: Throwable) {
        val original = reported.originalRecipeFailure()
        // Preserve the existing direct critical path without allocating traversal state first.
        if (original.isCritical()) return observe(original)
        val pending = ArrayDeque<Throwable>().apply { add(original) }
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var truncated = false
        while (pending.isNotEmpty()) {
            val failure = pending.removeFirst().originalRecipeFailure()
            if (failure !in seen) {
                if (seen.size == MAX_FAILURE_EVIDENCE) {
                    truncated = true
                    break
                }
                seen.add(failure)
                observe(failure)
                // Resource cleanup failures can be suppressed onto an otherwise expected syntax error.
                // Inspect only suppressed edges; bound queued/visited evidence and detect cycles by identity.
                val evidence = failure.suppressed
                val capacity = MAX_FAILURE_EVIDENCE - pending.size
                if (evidence.size > capacity) truncated = true
                evidence.take(capacity).forEach(pending::addLast)
            }
        }
        if (truncated) {
            // Do not retain the oversized graph as a cause: logging it would defeat the evidence bound.
            firstEngineFailure.keepFirst(IllegalStateException("OpenRewrite failure evidence limit exceeded"))
        }
    }

    private fun observe(failure: Throwable) {
        firstFailure.keepFirst(failure)
        if (failure.isCritical()) {
            throw criticalFailure.keepFirst(failure)
        }
        if (!failure.isExpectedSourceError()) firstEngineFailure.keepFirst(failure)
    }

    /** Records [failure] unless an earlier one was recorded, and returns the one kept. */
    private fun SharedValue<Throwable?>.keepFirst(failure: Throwable): Throwable =
        checkNotNull(update { it ?: failure })

    fun throwIfFailed(allowSourceErrors: Boolean = false) {
        val failure = criticalFailure.value ?: firstEngineFailure.value
            ?: firstFailure.value.takeUnless { allowSourceErrors }
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
        const val MAX_FAILURE_EVIDENCE = 32
        val logger = LoggerFactory.getLogger(OpenRewriteFormatter::class.java)
    }
}
