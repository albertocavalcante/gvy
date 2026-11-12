package com.github.albertocavalcante.gvy.gls.e2e

import java.util.concurrent.CancellationException

/** Records only authored IDs. A sealed snapshot is never changed by a late worker. */
internal class NamedObservationRecorder(private val planned: List<String>, private val source: String) {
    private val observed = mutableListOf<ObservationResult>()
    private var sealed = false
    private var workspaceClosed = false
    private var sessionClosed = false
    private var workerFinished = false
    private var executorClosed = false
    private val cleanupFailures = mutableListOf<Throwable>()
    private var primaryFailure: Throwable? = null
    private var executing = false

    @Synchronized fun executionStarted() {
        if (!sealed) executing = true
    }

    @Synchronized fun primaryFailure(failure: Throwable) {
        if (!sealed && primaryFailure == null) primaryFailure = failure
    }

    @Synchronized fun workerFinished() {
        if (!sealed) workerFinished = true
    }

    @Synchronized fun executorClosed(failure: Throwable?) {
        if (!sealed) {
            executorClosed = failure == null
            if (failure != null) cleanupFailures.add(failure)
        }
    }

    fun observe(check: JsonCheck, evaluate: () -> Unit) {
        val id = check.id ?: return evaluate()
        val failure = try {
            evaluate()
            null
        } catch (failure: AssertionError) {
            failure
        }
        val status = when {
            check.xfail == null && failure == null -> ObservationStatus.Passed
            check.xfail == null -> ObservationStatus.Failed(BoundedFailureEvidence.capture(failure!!).text)
            else -> when (val decision = XfailOutcome.classify(check.xfail, source, failure)) {
                is XfailDecision.Expected -> ObservationStatus.KnownFailure(
                    issue = check.xfail.issue,
                    reason = check.xfail.reason,
                    match = check.xfail.match,
                    evidence = BoundedFailureEvidence.capture(requireNotNull(failure)),
                )
                is XfailDecision.Fixed -> ObservationStatus.UnexpectedPass(check.xfail.issue)
                is XfailDecision.Unexpected -> ObservationStatus.Failed(
                    BoundedFailureEvidence.capture(decision.failure).text,
                )
            }
        }
        synchronized(this) { if (!sealed) observed.add(ObservationResult(id, status)) }
        if (failure != null && status is ObservationStatus.Failed) throw failure
    }

    fun closeSession(close: () -> Unit) = closeResource(close) { sessionClosed = true }
    fun closeWorkspace(close: () -> Unit) = closeResource(close) { workspaceClosed = true }

    private fun closeResource(close: () -> Unit, markClosed: () -> Unit) {
        try {
            close()
        } catch (failure: Throwable) {
            synchronized(this) { if (!sealed) cleanupFailures.add(failure) }
            throw failure
        } finally {
            synchronized(this) { if (!sealed) markClosed() }
        }
    }

    @Synchronized fun finish(failure: Throwable?): ScenarioOutcome {
        sealed = true
        val confirmed = workerFinished && executorClosed && workspaceClosed && sessionClosed
        val cleanup = when {
            cleanupFailures.isNotEmpty() -> CleanupOutcome.Failed(
                BoundedFailureEvidence.captureAll(cleanupFailures).text,
                confirmed = confirmed,
            )
            confirmed -> CleanupOutcome.Succeeded
            else -> CleanupOutcome.Unconfirmed("Scenario resources did not finish cleanup before reporting")
        }
        val phase = if (executing) ScenarioFailurePhase.Execution else ScenarioFailurePhase.Setup
        val executionFailure = failure?.takeUnless { candidate -> cleanupFailures.any { it === candidate } }
            ?: primaryFailure
        val completion = when (executionFailure) {
            null -> ScenarioCompletion.Completed
            is InterruptedException, is CancellationException -> ScenarioCompletion.Cancelled(
                BoundedFailureEvidence.capture(executionFailure).text,
            )
            is AssertionError -> ScenarioCompletion.Failed(
                phase,
                ScenarioFailureKind.Product,
                BoundedFailureEvidence.capture(executionFailure).text,
            )
            else -> ScenarioCompletion.Failed(
                phase,
                ScenarioFailureKind.Unclassified,
                BoundedFailureEvidence.capture(executionFailure).text,
            )
        }
        return ScenarioOutcome(planned, observed, cleanup, completion)
    }
}
