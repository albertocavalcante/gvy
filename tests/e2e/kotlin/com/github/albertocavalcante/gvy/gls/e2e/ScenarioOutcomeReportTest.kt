// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import org.opentest4j.TestAbortedException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Isolated
class ScenarioOutcomeReportTest {
    @TempDir lateinit var tempDir: Path
    private var previousDirectory: String? = null
    private val reportDirectory get() = tempDir.resolve("reports")

    @BeforeEach
    fun configureReportDirectory() {
        previousDirectory = System.getProperty(REPORT_DIRECTORY_PROPERTY)
        System.setProperty(REPORT_DIRECTORY_PROPERTY, reportDirectory.toString())
    }

    @AfterEach
    fun restoreReportDirectory() {
        val previous = previousDirectory
        if (previous == null) {
            System.clearProperty(REPORT_DIRECTORY_PROPERTY)
        } else {
            System.setProperty(REPORT_DIRECTORY_PROPERTY, previous)
        }
    }

    @Test
    fun `known failure is persisted before JUnit abort with later passing checks`() {
        val outcome = ScenarioOutcome(
            listOf("known", "later"),
            listOf(
                ObservationResult("known", ObservationStatus.KnownFailure(42)),
                ObservationResult("later", ObservationStatus.Passed),
            ),
            CleanupOutcome.Succeeded,
        )

        assertThatThrownBy { run(outcome) }.isInstanceOf(TestAbortedException::class.java)

        val report = report()
        assertThat(report.getValue("isSuccessful").jsonPrimitive.content).isEqualTo("false")
        val expectedCounts = Json.parseToJsonElement(
            """{"planned":2,"executed":2,"passed":1,"knownFailed":1,"failed":0,"unexpectedPass":0,"blocked":0}""",
        )
        assertThat(report.getValue("counts")).isEqualTo(expectedCounts)
        assertThat(report.getValue("planned").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("known", "later")
        val observed = report.getValue("observed").jsonArray
        assertThat(observed.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            .containsExactly("known", "later")
        assertThat(observed[0].jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("known_failure")
        assertThat(observed[0].jsonObject.getValue("issue").jsonPrimitive.content).isEqualTo("42")
        assertThat(observed[1].jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("passed")
    }

    @Test
    fun `required failure persists its detail and blocked IDs before JUnit fails`() {
        val outcome = ScenarioOutcome(
            listOf("failed", "unreached"),
            listOf(ObservationResult("failed", ObservationStatus.Failed("wrong current declaration"))),
            CleanupOutcome.Succeeded,
            ScenarioCompletion.Failed(ScenarioFailurePhase.Execution, ScenarioFailureKind.Product, "assertion failed"),
        )

        assertThatThrownBy { run(outcome) }.isInstanceOf(AssertionError::class.java)

        val report = report()
        assertThat(report.getValue("blocked").jsonArray.map { it.jsonPrimitive.content }).containsExactly("unreached")
        val observation = report.getValue("observed").jsonArray.single().jsonObject
        assertThat(observation.getValue("detail").jsonPrimitive.content).isEqualTo("wrong current declaration")
        val completion = report.getValue("completion").jsonObject
        assertThat(completion.getValue("phase").jsonPrimitive.content).isEqualTo("Execution")
        assertThat(completion.getValue("kind").jsonPrimitive.content).isEqualTo("Product")
    }

    @Test
    fun `cancellation retains its own outcome and failed cleanup`() {
        val outcome = ScenarioOutcome(
            emptyList(),
            emptyList(),
            CleanupOutcome.Failed("server still alive", confirmed = false),
            ScenarioCompletion.Cancelled("interrupted"),
        )

        assertThatThrownBy { run(outcome) }.isInstanceOf(java.util.concurrent.CancellationException::class.java)

        val report = report()
        assertThat(report.getValue("completion").jsonObject.getValue("status").jsonPrimitive.content)
            .isEqualTo("cancelled")
        val cleanup = report.getValue("cleanup").jsonObject
        assertThat(cleanup.getValue("confirmed").jsonPrimitive.content).isEqualTo("false")
        assertThat(cleanup.getValue("detail").jsonPrimitive.content).isEqualTo("server still alive")
    }

    @Test
    fun `unexpected pass remains distinct from a known failure`() {
        val outcome = ScenarioOutcome(
            listOf("fixed"),
            listOf(ObservationResult("fixed", ObservationStatus.UnexpectedPass(42))),
            CleanupOutcome.Succeeded,
        )

        assertThatThrownBy { run(outcome) }.isInstanceOf(AssertionError::class.java)

        val observation = report().getValue("observed").jsonArray.single().jsonObject
        assertThat(observation.getValue("status").jsonPrimitive.content).isEqualTo("unexpected_pass")
        assertThat(observation.getValue("issue").jsonPrimitive.content).isEqualTo("42")
    }

    @Test
    fun `report IO failure cannot be hidden as a known product failure`() {
        val regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "occupied")
        System.setProperty(REPORT_DIRECTORY_PROPERTY, regularFile.toString())
        val outcome = ScenarioOutcome(
            listOf("known"),
            listOf(ObservationResult("known", ObservationStatus.KnownFailure(42))),
            CleanupOutcome.Succeeded,
        )

        assertThatThrownBy { run(outcome) }.isInstanceOf(IOException::class.java)
        assertThat(Files.readString(regularFile)).isEqualTo("occupied")
    }

    @Test
    fun `report IO failure retains cancellation and its original cleanup evidence`() {
        val regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "occupied")
        System.setProperty(REPORT_DIRECTORY_PROPERTY, regularFile.toString())
        val outcome = ScenarioOutcome(
            emptyList(),
            emptyList(),
            CleanupOutcome.Unconfirmed("worker live"),
            ScenarioCompletion.Cancelled("interrupted"),
        )

        val failure = checkNotNull(catchThrowable { run(outcome) })
        assertThat(failure).isInstanceOf(java.util.concurrent.CancellationException::class.java)
        val io = failure.suppressed.single()
        assertThat(io).isInstanceOf(IOException::class.java)
        assertThat(io.suppressed.single().message).contains("worker live", "cancelled", "unconfirmed")
    }

    @Test
    fun `unconfirmed cleanup is persisted without claiming a successful run`() {
        val outcome = ScenarioOutcome(emptyList(), emptyList(), CleanupOutcome.Unconfirmed("worker live"))

        assertThatThrownBy { run(outcome) }.isInstanceOf(AssertionError::class.java)

        val report = report()
        assertThat(report.getValue("isSuccessful").jsonPrimitive.content).isEqualTo("false")
        assertThat(report.getValue("cleanup").jsonObject.getValue("status").jsonPrimitive.content)
            .isEqualTo("unconfirmed")
    }

    @Test
    fun `Bazel outputs are used only when no explicit report directory is configured`() {
        val bazelRoot = tempDir.resolve("bazel-outputs").toString()
        assertThat(ScenarioOutcomeReport.outputDirectory(null, bazelRoot))
            .isEqualTo(Path.of(bazelRoot, "scenario-outcomes"))
        assertThat(ScenarioOutcomeReport.outputDirectory(reportDirectory.toString(), bazelRoot))
            .isEqualTo(reportDirectory)
        assertThatThrownBy { ScenarioOutcomeReport.outputDirectory(" ", bazelRoot) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ScenarioOutcomeReport.outputDirectory(null, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `invalid report paths retain sealed evidence without hiding cancellation`() {
        for (configured in listOf(" ", "\u0000")) {
            System.setProperty(REPORT_DIRECTORY_PROPERTY, configured)
            val outcome = ScenarioOutcome(
                listOf("known"),
                listOf(ObservationResult("known", ObservationStatus.KnownFailure(42))),
                CleanupOutcome.Succeeded,
            )

            val failure = checkNotNull(catchThrowable { run(outcome) })
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure.suppressed.single().message).contains("known_failure", "42")

            val cancelled = ScenarioOutcome(
                emptyList(),
                emptyList(),
                CleanupOutcome.Unconfirmed("worker live"),
                ScenarioCompletion.Cancelled("interrupted"),
            )
            val cancellation = checkNotNull(catchThrowable { run(cancelled) })
            assertThat(cancellation).isInstanceOf(java.util.concurrent.CancellationException::class.java)
            val reportFailure = cancellation.suppressed.single()
            assertThat(reportFailure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(reportFailure.suppressed.single().message).contains("cancelled", "worker live")
        }
    }

    @Test
    fun `repeated scenario executions retain separate complete reports`() {
        val outcome = ScenarioOutcome(
            listOf("passed"),
            listOf(ObservationResult("passed", ObservationStatus.Passed)),
            CleanupOutcome.Succeeded,
        )

        run(outcome)
        run(outcome)

        assertThat(Files.isDirectory(reportDirectory)).isTrue()
        val paths = Files.list(reportDirectory).use { it.toList() }
        assertThat(paths).hasSize(2)
        val reports = paths.map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
        assertThat(reports.map { it.getValue("executionId").jsonPrimitive.content }).doesNotHaveDuplicates()
        assertThat(reports.map { it.getValue("isSuccessful").jsonPrimitive.content }).containsOnly("true")
        assertThat(reports.map { it.getValue("scenario").jsonPrimitive.content }).containsOnly("recovery")
        assertThat(reports.map { it.getValue("schemaVersion").jsonPrimitive.content }).containsOnly("1")
    }

    private fun run(outcome: ScenarioOutcome) {
        val check = JsonCheck("$.present", JsonExpectation(), id = "present")
        val definition = ScenarioDefinition(
            Scenario("recovery", steps = listOf(ScenarioStep.Assert(source = "workspace", checks = listOf(check)))),
            source = "recovery.yaml",
        )
        val executor = mockk<ScenarioExecutor>()
        every { executor.executeWithOutcome(definition) } returns outcome
        ScenarioRunnerExecution.run(definition, executor)
    }

    private fun report(): JsonObject {
        assertThat(Files.isDirectory(reportDirectory)).describedAs("Persist outcome before JUnit projection").isTrue()
        val paths = Files.list(reportDirectory).use { it.toList() }
        assertThat(paths).hasSize(1)
        return Json.parseToJsonElement(Files.readString(paths.single())).jsonObject
    }

    companion object {
        private const val REPORT_DIRECTORY_PROPERTY = "groovy.lsp.e2e.outcomeDir"
    }
}
