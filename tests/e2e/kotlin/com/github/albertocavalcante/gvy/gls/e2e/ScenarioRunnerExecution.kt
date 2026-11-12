package com.github.albertocavalcante.gvy.gls.e2e

import org.junit.jupiter.api.Assumptions
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Applies JUnit policy only after execution and cleanup have produced a sealed outcome. */
internal object ScenarioOutcomeJUnit {
    fun report(outcome: ScenarioOutcome) {
        val problems = mutableListOf<String>()
        when (val completion = outcome.completion) {
            ScenarioCompletion.Completed -> Unit
            is ScenarioCompletion.Failed -> problems.add("${completion.phase} ${completion.kind}: ${completion.detail}")
            is ScenarioCompletion.Cancelled -> problems.add("cancelled: ${completion.detail}")
        }
        when (val cleanup = outcome.cleanup) {
            CleanupOutcome.Succeeded -> Unit
            is CleanupOutcome.Failed -> problems.add(
                "cleanup failed (confirmed=${cleanup.confirmed}): ${cleanup.detail}",
            )
            is CleanupOutcome.Unconfirmed -> problems.add("cleanup unconfirmed: ${cleanup.detail}")
        }
        if (outcome.blocked.isNotEmpty()) problems.add("blocked: ${outcome.blocked.joinToString()}")
        for (result in outcome.observed) {
            when (val status = result.status) {
                is ObservationStatus.Failed -> problems.add("${result.id}: ${status.detail}")
                is ObservationStatus.UnexpectedPass -> problems.add(
                    "${result.id}: unexpected pass for gvy#${status.issue}",
                )
                else -> Unit
            }
        }
        val completion = outcome.completion
        if (completion is ScenarioCompletion.Cancelled) {
            val cancellation = CancellationException(completion.detail)
            if (problems.size > 1) cancellation.addSuppressed(AssertionError(problems.drop(1).joinToString("\n")))
            throw cancellation
        }
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
        val known = outcome.observed.mapNotNull { (it.status as? ObservationStatus.KnownFailure)?.issue }
        if (known.isNotEmpty()) Assumptions.abort<Unit>("Known failures: ${known.joinToString { "gvy#$it" }}")
    }
}

/** Legacy whole-scenario XFAIL remains in place until its scenarios migrate to named checks. */
internal object ScenarioRunnerExecution {
    fun run(definition: ScenarioDefinition, executor: ScenarioExecutor) {
        if (hasNamedCheckMetadata(definition)) {
            ScenarioOutcomeJUnit.report(executor.executeWithOutcome(definition))
            return
        }
        val xfail = definition.scenario.xfail
        if (xfail == null) {
            executor.execute(definition)
        } else {
            XfailOutcome.run(xfail, Path.of(definition.source).fileName.toString()) { executor.execute(definition) }
        }
    }
}
