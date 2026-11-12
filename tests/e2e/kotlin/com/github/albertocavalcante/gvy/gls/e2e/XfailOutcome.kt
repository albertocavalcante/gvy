package com.github.albertocavalcante.gvy.gls.e2e

import com.google.re2j.Pattern
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assumptions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
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
        validateDeclaration(xfail)
        if (failure == null) {
            return XfailDecision.Fixed("gvy#${xfail.issue} appears fixed: remove xfail from $source")
        }
        val pattern = xfail.match?.let(::compileMatch)
        if (failure !is AssertionError && failure !is TimeoutException && failure !is ExecutionException) {
            return XfailDecision.Unexpected(failure)
        }
        val inspection = try {
            inspect(failure)
        } catch (incomplete: InspectionIncomplete) {
            return XfailDecision.Unexpected(IncompleteXfailClassification(incomplete.message.orEmpty(), failure))
        }
        if (inspection.suppressed) return XfailDecision.Unexpected(failure)
        val product = when (failure) {
            is AssertionError, is TimeoutException -> true
            is ExecutionException -> inspection.cause is ResponseErrorException
            else -> false
        }
        if (!product) return XfailDecision.Unexpected(failure)
        if (failure is TimeoutException && pattern == null) return XfailDecision.Unexpected(failure)
        if (pattern != null && !pattern.matcher(inspection.messages).find()) {
            return XfailDecision.Unexpected(failure)
        }
        return XfailDecision.Expected(
            "xfail gvy#${xfail.issue}: ${xfail.reason} (${inspection.firstMessage.lineSequence().firstOrNull().orEmpty()})",
        )
    }

    internal fun validateDeclaration(xfail: Xfail) {
        require(xfail.issue > 0) { "xfail.issue must be a gvy issue number" }
        require(xfail.reason.length <= MAX_REASON_CHARS) { "xfail reason exceeds $MAX_REASON_CHARS characters" }
        require(xfail.reason.isNotBlank()) { "xfail.reason must say what is wrong" }
        xfail.match?.let(::compileMatch)
    }

    /** RE2/J is linear in input length; both compiled pattern and inspected messages have fixed caps. */
    internal fun compileMatch(value: String): Pattern {
        require(value.length <= MAX_PATTERN_CHARS) { "xfail match exceeds $MAX_PATTERN_CHARS characters" }
        return try {
            Pattern.compile(value)
        } catch (failure: com.google.re2j.PatternSyntaxException) {
            throw IllegalArgumentException("Unsupported XFAIL match pattern: ${failure.message}", failure)
        }
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

    private data class Inspection(
        val messages: String,
        val firstMessage: String,
        val cause: Throwable?,
        val suppressed: Boolean,
    )

    private fun inspect(root: Throwable): Inspection {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val messages = StringBuilder()
        var firstMessage = ""
        var firstCause: Throwable? = null
        var current: Throwable? = root
        var nodes = 0
        var messageCount = 0
        while (current != null && seen.add(current)) {
            if (++nodes > MAX_CAUSE_NODES) throw InspectionIncomplete("cause chain exceeds $MAX_CAUSE_NODES nodes")
            val node = current
            val suppressed = inspectValue { node.suppressed }
            if (suppressed.isNotEmpty()) return Inspection("", "", null, true)
            val message = inspectValue { node.message }
            if (nodes == 1) firstMessage = message.orEmpty()
            if (message != null) {
                val remaining = MAX_MESSAGE_CHARS - messages.length - if (messageCount > 0) 1 else 0
                if (remaining < 0 || message.length > remaining) {
                    throw InspectionIncomplete("failure messages exceed $MAX_MESSAGE_CHARS characters")
                }
                if (messageCount++ > 0) messages.append('\n')
                messages.append(message)
            }
            current = inspectValue { node.cause }
            if (nodes == 1) firstCause = current
        }
        return Inspection(messages.toString(), firstMessage, firstCause, false)
    }

    private fun <T> inspectValue(block: () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    } catch (failure: Exception) {
        throw InspectionIncomplete("failure evidence unavailable (${failure.javaClass.simpleName})")
    }

    private class InspectionIncomplete(message: String) : RuntimeException(message)
    private const val MAX_CAUSE_NODES = 32
    private const val MAX_MESSAGE_CHARS = 8_192
    private const val MAX_PATTERN_CHARS = 256
    private const val MAX_REASON_CHARS = 512
}

/** The original failure remains the cause; this result must never be treated as an expected failure. */
internal class IncompleteXfailClassification(reason: String, failure: Throwable) :
    IllegalStateException("XFAIL classification incomplete: $reason", failure)
