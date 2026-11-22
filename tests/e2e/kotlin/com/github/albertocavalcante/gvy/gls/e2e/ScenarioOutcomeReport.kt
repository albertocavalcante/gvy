// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException

/** Persists sealed named results before JUnit projects them into pass, failure or abort. */
internal object ScenarioOutcomeReport {
    const val DIRECTORY_PROPERTY = "groovy.lsp.e2e.outcomeDir"

    fun write(definition: ScenarioDefinition, outcome: ScenarioOutcome, evidence: JsonObject? = null) {
        val executionId = UUID.randomUUID().toString()
        val payload = encode(definition, executionId, outcome, evidence).toString()
        try {
            val directory = outputDirectory(
                System.getProperty(DIRECTORY_PROPERTY),
                System.getenv("TEST_UNDECLARED_OUTPUTS_DIR"),
            )
            publish(directory, executionId, payload)
        } catch (failure: IOException) {
            publicationFailed(failure, payload, outcome)
        } catch (failure: IllegalArgumentException) {
            publicationFailed(failure, payload, outcome)
        } catch (failure: IllegalStateException) {
            publicationFailed(failure, payload, outcome)
        } catch (failure: SecurityException) {
            publicationFailed(failure, payload, outcome)
        }
    }

    private fun publicationFailed(failure: Exception, payload: String, outcome: ScenarioOutcome): Nothing {
        failure.addSuppressed(IllegalStateException("Unpublished named outcome: $payload"))
        val completion = outcome.completion
        if (completion is ScenarioCompletion.Cancelled) {
            throw CancellationException(completion.detail).apply { addSuppressed(failure) }
        }
        throw failure
    }

    internal fun outputDirectory(configured: String?, bazelOutputs: String?): Path {
        if (configured != null) {
            require(configured.isNotBlank()) { "$DIRECTORY_PROPERTY must not be blank" }
            return Path.of(configured)
        }
        check(!bazelOutputs.isNullOrBlank()) { "Configure $DIRECTORY_PROPERTY for named scenario reports" }
        return Path.of(bazelOutputs, "scenario-outcomes")
    }

    private fun publish(directory: Path, executionId: String, payload: String) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".$executionId-", ".tmp")
        try {
            Files.writeString(temporary, payload)
            Files.move(temporary, directory.resolve("$executionId.json"), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            try {
                Files.deleteIfExists(temporary)
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun encode(
        definition: ScenarioDefinition,
        executionId: String,
        outcome: ScenarioOutcome,
        evidence: JsonObject?,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("executionId", executionId)
        put("scenario", definition.scenario.name)
        put("source", definition.source)
        put("isSuccessful", outcome.isSuccessful)
        put("planned", buildJsonArray { outcome.planned.forEach { add(it) } })
        put("blocked", buildJsonArray { outcome.blocked.forEach { add(it) } })
        put("observed", buildJsonArray { outcome.observed.forEach { add(observation(it)) } })
        putJsonObject("counts") {
            put("planned", outcome.counts.planned)
            put("executed", outcome.counts.executed)
            put("passed", outcome.counts.passed)
            put("knownFailed", outcome.counts.knownFailed)
            put("failed", outcome.counts.failed)
            put("unexpectedPass", outcome.counts.unexpectedPass)
            put("blocked", outcome.counts.blocked)
        }
        put("completion", completion(outcome.completion))
        put("cleanup", cleanup(outcome.cleanup))
        evidence?.let { put("evidence", it) }
    }

    private fun observation(result: ObservationResult): JsonObject = buildJsonObject {
        put("id", result.id)
        when (val status = result.status) {
            ObservationStatus.Passed -> put("status", "passed")
            is ObservationStatus.KnownFailure -> {
                put("status", "known_failure")
                put("issue", status.issue)
            }
            is ObservationStatus.Failed -> {
                put("status", "failed")
                put("detail", status.detail)
            }
            is ObservationStatus.UnexpectedPass -> {
                put("status", "unexpected_pass")
                put("issue", status.issue)
            }
        }
    }

    private fun completion(value: ScenarioCompletion): JsonObject = buildJsonObject {
        when (value) {
            ScenarioCompletion.Completed -> put("status", "completed")
            is ScenarioCompletion.Failed -> {
                put("status", "failed")
                put("phase", value.phase.name)
                put("kind", value.kind.name)
                put("detail", value.detail)
            }
            is ScenarioCompletion.Cancelled -> {
                put("status", "cancelled")
                put("detail", value.detail)
            }
        }
    }

    private fun cleanup(value: CleanupOutcome): JsonObject = buildJsonObject {
        when (value) {
            CleanupOutcome.Succeeded -> {
                put("status", "succeeded")
                put("confirmed", true)
            }
            is CleanupOutcome.Failed -> {
                put("status", "failed")
                put("confirmed", value.confirmed)
                put("detail", value.detail)
            }
            is CleanupOutcome.Unconfirmed -> {
                put("status", "unconfirmed")
                put("confirmed", false)
                put("detail", value.detail)
            }
        }
    }
}
