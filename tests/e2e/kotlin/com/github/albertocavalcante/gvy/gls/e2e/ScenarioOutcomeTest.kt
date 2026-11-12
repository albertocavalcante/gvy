package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScenarioOutcomeTest {
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
}
