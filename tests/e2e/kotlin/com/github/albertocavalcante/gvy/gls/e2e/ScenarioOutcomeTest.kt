package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScenarioOutcomeTest {
    @Test
    fun `complete passed observations and successful cleanup report success`() {
        val outcome = ScenarioOutcome(
            listOf("a", "b"),
            listOf(ObservationResult("a", ObservationStatus.Passed), ObservationResult("b", ObservationStatus.Passed)),
            CleanupOutcome.Succeeded,
        )
        assertThat(outcome.isSuccessful).isTrue()
        assertThat(outcome.counts).isEqualTo(ObservationCounts(2, 2, 2, 0, 0, 0, 0))
    }

    @Test
    fun `empty completed scenario has no invented checks`() {
        val outcome = ScenarioOutcome(emptyList(), emptyList(), CleanupOutcome.Succeeded)
        assertThat(outcome.isSuccessful).isTrue()
        assertThat(outcome.counts).isEqualTo(ObservationCounts(0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `unreached observations remain blocked and cleanup failure remains separate`() {
        val outcome = ScenarioOutcome(
            planned = listOf("number", "list", "map"),
            observed = listOf(ObservationResult("number", ObservationStatus.KnownFailure(issue = 432))),
            cleanup = CleanupOutcome.Failed("session close failed"),
        )

        assertThat(outcome.counts).isEqualTo(ObservationCounts(planned = 3, executed = 1, passed = 0, knownFailed = 1, failed = 0, unexpectedPass = 0, blocked = 2))
        assertThat(outcome.blocked).containsExactly("list", "map")
        assertThat(outcome.cleanup).isEqualTo(CleanupOutcome.Failed("session close failed"))
        assertThat(outcome.isSuccessful).isFalse()
    }

    @Test
    fun `wrong observation and duplicate identifiers are rejected`() {
        val pass = ObservationResult("other", ObservationStatus.Passed)
        assertThatThrownBy { ScenarioOutcome(listOf("a"), listOf(pass), CleanupOutcome.Succeeded) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ScenarioOutcome(listOf("a", "a"), emptyList(), CleanupOutcome.Succeeded) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ScenarioOutcome(listOf("a"), listOf(ObservationResult("a", ObservationStatus.Passed), ObservationResult("a", ObservationStatus.Passed)), CleanupOutcome.Succeeded)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unexpected pass and unconfirmed cleanup cannot report success`() {
        val outcome = ScenarioOutcome(
            planned = listOf("a"),
            observed = listOf(ObservationResult("a", ObservationStatus.UnexpectedPass(issue = 42))),
            cleanup = CleanupOutcome.Unconfirmed("deadline exceeded"),
        )
        assertThat(outcome.counts.unexpectedPass).isEqualTo(1)
        assertThat(outcome.isSuccessful).isFalse()
    }

    @Test
    fun `failure reasons independently prevent success`() {
        val statuses = listOf(
            ObservationStatus.KnownFailure(issue = 42),
            ObservationStatus.Failed("mismatch"),
            ObservationStatus.UnexpectedPass(issue = 42),
        )
        statuses.forEach { status ->
            assertThat(ScenarioOutcome(listOf("a"), listOf(ObservationResult("a", status)), CleanupOutcome.Succeeded).isSuccessful)
                .isFalse()
        }
        assertThat(ScenarioOutcome(listOf("a"), emptyList(), CleanupOutcome.Succeeded).isSuccessful).isFalse()
        listOf(CleanupOutcome.Failed("failed"), CleanupOutcome.Unconfirmed("unknown")).forEach { cleanup ->
            assertThat(ScenarioOutcome(emptyList(), emptyList(), cleanup).isSuccessful).isFalse()
        }
    }

    @Test
    fun `all terminal observation categories have distinct counts`() {
        val outcome = ScenarioOutcome(
            listOf("a", "b", "c", "d", "e"),
            listOf(
                ObservationResult("a", ObservationStatus.Passed),
                ObservationResult("b", ObservationStatus.KnownFailure(1)),
                ObservationResult("c", ObservationStatus.Failed("wrong")),
                ObservationResult("d", ObservationStatus.UnexpectedPass(2)),
            ),
            CleanupOutcome.Succeeded,
        )
        assertThat(outcome.counts).isEqualTo(ObservationCounts(5, 4, 1, 1, 1, 1, 1))
        assertThat(outcome.blocked).containsExactly("e")
    }

    @Test
    fun `scenario completion keeps cancellation and operational failures separate`() {
        val cancelled = ScenarioOutcome(emptyList(), emptyList(), CleanupOutcome.Succeeded, ScenarioCompletion.Cancelled("cancelled"))
        val failed = ScenarioOutcome(emptyList(), emptyList(), CleanupOutcome.Succeeded, ScenarioCompletion.Failed(ScenarioFailureKind.Transport, "broken"))
        assertThat(cancelled.isSuccessful).isFalse()
        assertThat(failed.isSuccessful).isFalse()
        assertThat(cancelled.completion).isInstanceOf(ScenarioCompletion.Cancelled::class.java)
        assertThat(failed.completion).isEqualTo(ScenarioCompletion.Failed(ScenarioFailureKind.Transport, "broken"))
    }

    @Test
    fun `outcome detaches caller lists and preserves planned order`() {
        val planned = mutableListOf("a", "b", "c")
        val observed = mutableListOf(ObservationResult("b", ObservationStatus.Passed))
        val outcome = ScenarioOutcome(planned, observed, CleanupOutcome.Succeeded)
        planned.clear()
        observed.clear()
        assertThat(outcome.planned).containsExactly("a", "b", "c")
        assertThat(outcome.blocked).containsExactly("a", "c")
        assertThat(outcome.counts.executed).isEqualTo(1)
    }
}
