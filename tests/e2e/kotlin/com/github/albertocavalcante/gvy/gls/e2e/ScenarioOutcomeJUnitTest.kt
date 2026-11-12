package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.util.concurrent.CancellationException

class ScenarioOutcomeJUnitTest {
    @Test
    fun `all passed reports normally`() {
        ScenarioOutcomeJUnit.report(outcome(listOf(ObservationStatus.Passed)))
    }

    @Test
    fun `known failure aborts only after all checks are accounted for`() {
        assertThatThrownBy {
            ScenarioOutcomeJUnit.report(outcome(listOf(ObservationStatus.KnownFailure(42), ObservationStatus.Passed)))
        }.isInstanceOf(TestAbortedException::class.java).hasMessageContaining("42")
    }

    @Test
    fun `known failure cannot hide an untested check`() {
        val report = ScenarioOutcome(
            planned = listOf("known", "blocked"),
            observed = listOf(ObservationResult("known", ObservationStatus.KnownFailure(42))),
            cleanup = CleanupOutcome.Succeeded,
        )
        assertThatThrownBy { ScenarioOutcomeJUnit.report(report) }
            .isInstanceOf(AssertionError::class.java).hasMessageContaining("blocked")
    }

    @Test
    fun `known failure cannot hide cleanup failure or unconfirmed cleanup`() {
        for (cleanup in listOf(CleanupOutcome.Failed("close failed"), CleanupOutcome.Unconfirmed("worker live"))) {
            assertThatThrownBy {
                ScenarioOutcomeJUnit.report(outcome(listOf(ObservationStatus.KnownFailure(42)), cleanup))
            }.isInstanceOf(AssertionError::class.java).hasMessageContaining("cleanup")
        }
    }

    @Test
    fun `unexpected pass and unrelated failure are not aborted as expected`() {
        for (status in listOf(ObservationStatus.UnexpectedPass(42), ObservationStatus.Failed("unexpected"))) {
            assertThatThrownBy {
                ScenarioOutcomeJUnit.report(outcome(listOf(ObservationStatus.KnownFailure(42), status)))
            }.isInstanceOf(AssertionError::class.java)
        }
    }

    @Test
    fun `cancellation remains distinct from expected failure`() {
        val report = ScenarioOutcome(
            planned = listOf("known"),
            observed = listOf(ObservationResult("known", ObservationStatus.KnownFailure(42))),
            cleanup = CleanupOutcome.Succeeded,
            completion = ScenarioCompletion.Cancelled("interrupted"),
        )
        assertThatThrownBy { ScenarioOutcomeJUnit.report(report) }
            .isInstanceOf(CancellationException::class.java).hasMessageContaining("interrupted")
    }

    @Test
    fun `cancellation retains independent cleanup failure`() {
        val report = ScenarioOutcome(
            planned = emptyList(),
            observed = emptyList(),
            cleanup = CleanupOutcome.Failed("close failed", confirmed = false),
            completion = ScenarioCompletion.Cancelled("interrupted"),
        )
        val failure = checkNotNull(catchThrowable { ScenarioOutcomeJUnit.report(report) })
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure.suppressed).singleElement().hasMessageContaining("close failed")
    }

    @Test
    fun `operational failure is not hidden by a known failure`() {
        val report = ScenarioOutcome(
            planned = listOf("known"),
            observed = listOf(ObservationResult("known", ObservationStatus.KnownFailure(42))),
            cleanup = CleanupOutcome.Succeeded,
            completion = ScenarioCompletion.Failed(
                ScenarioFailurePhase.Execution,
                ScenarioFailureKind.Transport,
                "request failed",
            ),
        )
        assertThatThrownBy { ScenarioOutcomeJUnit.report(report) }
            .isInstanceOf(AssertionError::class.java).hasMessageContaining("request failed")
    }

    private fun outcome(
        statuses: List<ObservationStatus>,
        cleanup: CleanupOutcome = CleanupOutcome.Succeeded,
    ): ScenarioOutcome {
        val ids = statuses.indices.map { "check-$it" }
        return ScenarioOutcome(ids, ids.zip(statuses, ::ObservationResult), cleanup)
    }
}
