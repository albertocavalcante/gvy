// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assumptions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** What an `xfail` scenario's result means; see [XfailOutcome.classify]. */
sealed interface XfailDecision {
    /** The scenario failed the way the `xfail` declares; the test is reported as aborted. */
    data class Expected(val message: String) : XfailDecision

    /** The scenario failed in a way the `xfail` does not cover, so the failure is real. */
    data class Unexpected(val failure: Throwable) : XfailDecision

    /** The scenario passed, so the `xfail` is stale. */
    data class Fixed(val message: String) : XfailDecision
}

/**
 * Turns the result of a scenario that declares `xfail` into a test outcome.
 *
 * Only failures that look like the product misbehaving count as expected: an assertion that did not hold, a timeout,
 * or an LSP error response (a timeout only when [Xfail.match] is set). A harness error (bad YAML, missing fixture,
 * crashed process) is never hidden. When [Xfail.match] is set the failure message must also match it, so a different
 * failure in the same scenario is not swallowed.
 * Suppressed failures may represent failed cleanup, so they always keep the result unexpected.
 */
object XfailOutcome {
    /** Short step timeout applied to `xfail` scenarios, so a hung request does not eat shard time. */
    const val STEP_TIMEOUT_CAP_MS = 15_000L

    /** The step timeout to use: [requestedMs], lowered to [capMs] when a cap is set. */
    fun capTimeout(requestedMs: Long, capMs: Long?): Long = capMs?.let { minOf(requestedMs, it) } ?: requestedMs

    /**
     * Readiness waits are not part of the bug under test and depend on machine load (dependency resolution can be
     * slow), so the cap never applies to them; `initialize` is likewise never capped.
     */
    fun isReadinessWait(method: String): Boolean = method == "groovy/status"

    fun displayName(scenario: Scenario): String =
        scenario.xfail?.let { "[xfail gvy#${it.issue}] ${scenario.name}" } ?: scenario.name

    /**
     * Pure: [failure] is `null` when the scenario passed. [source] is the scenario file named in the "fixed" message.
     */
    fun classify(xfail: Xfail, source: String, failure: Throwable?): XfailDecision {
        if (failure == null) {
            return XfailDecision.Fixed("gvy#${xfail.issue} appears fixed: remove xfail from $source")
        }
        if (hasSuppressedFailure(failure)) return XfailDecision.Unexpected(failure)
        if (!isProductFailure(failure)) return XfailDecision.Unexpected(failure)
        // A timeout says little about why, and a slow machine can cause one, so it only counts with a `match`.
        if (failure is TimeoutException && xfail.match == null) return XfailDecision.Unexpected(failure)
        val pattern = xfail.match?.let(::Regex)
        if (pattern != null && !pattern.containsMatchIn(messageChain(failure))) {
            return XfailDecision.Unexpected(failure)
        }
        return XfailDecision.Expected(
            "xfail gvy#${xfail.issue}: ${xfail.reason} (${failure.message?.lineSequence()?.firstOrNull().orEmpty()})",
        )
    }

    /** Runs [body] and applies [classify]: aborts when expected, throws when unexpected or fixed. */
    fun run(xfail: Xfail, source: String, body: () -> Unit) {
        val failure = try {
            body()
            null
        } catch (failure: AssertionError) {
            failure
        } catch (failure: TimeoutException) {
            failure
        } catch (failure: ExecutionException) {
            failure
        }
        when (val decision = classify(xfail, source, failure)) {
            is XfailDecision.Expected -> Assumptions.abort<Unit>(decision.message)
            is XfailDecision.Unexpected -> throw decision.failure
            is XfailDecision.Fixed -> throw AssertionError(decision.message)
        }
    }

    private fun isProductFailure(failure: Throwable): Boolean = when (failure) {
        is AssertionError, is TimeoutException -> true
        is ExecutionException -> failure.cause is ResponseErrorException
        else -> false
    }

    private fun causeChain(failure: Throwable): Sequence<Throwable> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return generateSequence(failure) { it.cause }.takeWhile { visited.add(it) }
    }

    private fun hasSuppressedFailure(failure: Throwable): Boolean =
        causeChain(failure).any { it.suppressed.isNotEmpty() }

    private fun messageChain(failure: Throwable): String =
        causeChain(failure).mapNotNull { it.message }.joinToString("\n")
}
