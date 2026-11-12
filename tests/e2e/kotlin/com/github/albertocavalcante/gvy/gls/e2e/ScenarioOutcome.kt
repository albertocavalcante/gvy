package com.github.albertocavalcante.gvy.gls.e2e

import java.util.Collections

/** The terminal result of one named observation that was actually reached. */
sealed interface ObservationStatus {
    data object Passed : ObservationStatus
    data class KnownFailure(val issue: Int) : ObservationStatus {
        init {
            require(issue > 0) { "An expected failure requires a positive issue number" }
        }
    }
    data class Failed(val detail: String) : ObservationStatus
    data class UnexpectedPass(val issue: Int) : ObservationStatus {
        init {
            require(issue > 0) { "An unexpected pass requires a positive issue number" }
        }
    }
}

data class ObservationResult(val id: String, val status: ObservationStatus)

data class ObservationCounts(
    val planned: Int,
    val executed: Int,
    val passed: Int,
    val knownFailed: Int,
    val failed: Int,
    val unexpectedPass: Int,
    val blocked: Int,
)

/** Cleanup is independent of the product observations. */
sealed interface CleanupOutcome {
    data object Succeeded : CleanupOutcome
    /** A known cleanup failure can coexist with unconfirmed cleanup of other resources. */
    data class Failed(val detail: String, val confirmed: Boolean = true) : CleanupOutcome
    data class Unconfirmed(val detail: String) : CleanupOutcome
}

enum class ScenarioFailureKind { Specification, Product, Transport, Harness }
enum class ScenarioFailurePhase { Setup, Execution }

sealed interface ScenarioCompletion {
    data object Completed : ScenarioCompletion
    data class Failed(val phase: ScenarioFailurePhase, val kind: ScenarioFailureKind, val detail: String) :
        ScenarioCompletion
    data class Cancelled(val detail: String) : ScenarioCompletion
}

/** Immutable accounting for authored IDs; missing results are untested, not passes. */
class ScenarioOutcome(
    planned: List<String>,
    observed: List<ObservationResult>,
    val cleanup: CleanupOutcome,
    val completion: ScenarioCompletion = ScenarioCompletion.Completed,
) {
    val planned: List<String> = Collections.unmodifiableList(planned.toList())
    val observed: List<ObservationResult> = Collections.unmodifiableList(observed.toList())
    val blocked: List<String>
    val counts: ObservationCounts

    /** True only when all planned checks passed and execution and cleanup completed successfully.
     * Known failures remain visible and do not count as a pass; a runner decides how to report them.
     */
    val isSuccessful: Boolean

    init {
        require(this.planned.all { it.isNotBlank() }) { "Observation IDs must not be blank" }
        val plannedIds = this.planned.toSet()
        require(plannedIds.size == this.planned.size) { "Observation IDs must be unique" }
        val observedIds = this.observed.map { it.id }
        require(observedIds.distinct().size == observedIds.size) { "An observation can have only one result" }
        require(observedIds.all { it in plannedIds }) { "Observed IDs must be planned" }
        val reached = observedIds.toSet()
        blocked = Collections.unmodifiableList(this.planned.filterNot { it in reached })
        counts = ObservationCounts(
            planned = this.planned.size,
            executed = this.observed.size,
            passed = this.observed.count { it.status is ObservationStatus.Passed },
            knownFailed = this.observed.count { it.status is ObservationStatus.KnownFailure },
            failed = this.observed.count { it.status is ObservationStatus.Failed },
            unexpectedPass = this.observed.count { it.status is ObservationStatus.UnexpectedPass },
            blocked = blocked.size,
        )
        isSuccessful = completion == ScenarioCompletion.Completed && cleanup == CleanupOutcome.Succeeded &&
            counts.blocked == 0 && counts.passed == counts.planned
    }
}
