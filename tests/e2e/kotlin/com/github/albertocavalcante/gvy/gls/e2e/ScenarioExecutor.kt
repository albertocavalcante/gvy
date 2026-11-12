package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

class ScenarioExecutor internal constructor(
    private val sessionFactory: LanguageServerSessionFactory,
    private val createExecution: (Duration) -> ScenarioExecution,
) {
    constructor(sessionFactory: LanguageServerSessionFactory) : this(sessionFactory, { ScenarioExecution(it) })

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val jsonPathConfig: Configuration = Configuration.builder()
        .jsonProvider(JsonSmartJsonProvider())
        .mappingProvider(JsonSmartMappingProvider())
        .build()

    private val stepExecutors: Map<KClass<out ScenarioStep>, StepExecutor<out ScenarioStep>> = mapOf(
        ScenarioStep.Initialize::class to InitializeStepExecutor(),
        ScenarioStep.Initialized::class to InitializedStepExecutor(),
        ScenarioStep.Shutdown::class to ShutdownStepExecutor(),
        ScenarioStep.Exit::class to ExitStepExecutor(),
        ScenarioStep.OpenDocument::class to OpenDocumentStepExecutor(),
        ScenarioStep.ChangeDocument::class to ChangeDocumentStepExecutor(),
        ScenarioStep.SaveDocument::class to SaveDocumentStepExecutor(),
        ScenarioStep.CloseDocument::class to CloseDocumentStepExecutor(),
        ScenarioStep.SendRequest::class to SendRequestStepExecutor(),
        ScenarioStep.SendNotification::class to SendNotificationStepExecutor(),
        ScenarioStep.WaitNotification::class to WaitNotificationStepExecutor(),
        ScenarioStep.Assert::class to AssertStepExecutor(),
        ScenarioStep.Wait::class to WaitStepExecutor(),
        ScenarioStep.DownloadPlugin::class to DownloadPluginStepExecutor(),
        ScenarioStep.CliCommand::class to CliCommandStepExecutor(),
        ScenarioStep.GoldenAssert::class to GoldenAssertStepExecutor(),
        // High-level DSL step executors
        ScenarioStep.Completion::class to CompletionStepExecutor(),
        ScenarioStep.CodeAction::class to CodeActionStepExecutor(),
        ScenarioStep.Formatting::class to FormattingStepExecutor(),
        ScenarioStep.Rename::class to RenameStepExecutor(),
    )

    fun execute(definition: ScenarioDefinition) {
        require(!hasNamedCheckMetadata(definition)) {
            "Named checks require executeWithOutcome"
        }
        val execution = execution()
        execution.run(definition.scenario.name) { execute(definition, execution) }
    }

    /**
     * Executes named assert checks and returns an immutable report. Other check-bearing steps do not yet accept
     * named metadata. Operational failures without an established origin are reported as Unclassified; runner
     * integration is responsible for distinguishing transport failures and interpreting known failures.
     * An empty observation plan records no product verification. Unconfirmed cleanup may still be running.
     */
    fun executeWithOutcome(definition: ScenarioDefinition): ScenarioOutcome {
        val authored = checks(definition)
        require(definition.scenario.xfail == null) { "Scenario-level xfail cannot be combined with named outcomes" }
        authored.forEach { (step, check) ->
            require(check.xfail == null || check.id != null) { "A check xfail requires an ID" }
            require(step is ScenarioStep.Assert || (check.id == null && check.xfail == null)) {
                "Named checks are currently supported only in assert steps"
            }
            check.id?.let { require(it.isNotBlank()) { "A check ID must not be blank" } }
            check.xfail?.let {
                require(it.issue > 0 && it.reason.isNotBlank()) { "A check xfail needs an issue and reason" }
                it.match?.let(::Regex)
            }
        }
        val planned = authored.mapNotNull { it.second.id }
        require(planned.distinct().size == planned.size) { "Check IDs must be unique" }
        val recorder = NamedObservationRecorder(planned, definition.source)
        val execution = execution()
        val failure = try {
            execution.run(definition.scenario.name, recorder::executorClosed) {
                try {
                    execute(definition, execution, recorder)
                } finally {
                    recorder.workerFinished()
                }
            }
            null
        } catch (failure: AssertionError) {
            failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failure
        } catch (failure: Throwable) {
            failure
        }
        return recorder.finish(failure)
    }

    private fun execution(): ScenarioExecution {
        val timeoutMs = System.getProperty("groovy.lsp.e2e.scenarioTimeoutMs")?.let {
            requireNotNull(it.toLongOrNull()) { "groovy.lsp.e2e.scenarioTimeoutMs must be a positive integer" }
        } ?: DEFAULT_SCENARIO_TIMEOUT_MS
        return createExecution(Duration.ofMillis(timeoutMs))
    }

    private fun execute(
        definition: ScenarioDefinition,
        execution: ScenarioExecution,
        recorder: NamedObservationRecorder? = null,
    ) {
        execution.checkActive()
        val (workspace, inlineMarkers) = recordPrimary(recorder) { prepareWorkspace(definition) }
        val scenario = definition.scenario

        AutoCloseable {
            if (recorder == null) workspace.cleanup() else recorder.closeWorkspace { workspace.cleanup() }
        }.use {
            val session = ScenarioSession(execution) { sessionFactory.start(scenario.server, scenario.name) }
            AutoCloseable {
                if (recorder == null) session.close() else recorder.closeSession { session.close() }
            }.use {
                recordPrimary(recorder) {
                    val context = ScenarioContext(
                        definition = definition,
                        sessionAccess = ScenarioSessionAccess(session::get, execution::checkActive),
                        workspace = workspace,
                        json = json,
                        jsonPathConfig = jsonPathConfig,
                        timeoutCapMs = scenario.xfail?.let { XfailOutcome.STEP_TIMEOUT_CAP_MS },
                    )
                    context.registerBuiltInVariables()
                    context.namedAssertion = recorder?.let { it::observe }
                    inlineMarkers.forEach { (path, markers) ->
                        context.registerCursors(workspace.resolveUri(path), markers)
                    }

                    recorder?.executionStarted()
                    scenario.steps.forEachIndexed { index, step ->
                        execution.checkActive()
                        logger.info {
                            "Running step ${index + 1} (${step::class.simpleName}) for scenario '${scenario.name}'"
                        }
                        context.currentStepIndex = index
                        context.totalSteps = scenario.steps.size
                        val nextStep = scenario.steps.getOrNull(index + 1)

                        executeStep(step, context, nextStep)
                    }
                    execution.checkActive()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ScenarioStep> executeStep(step: T, context: ScenarioContext, nextStep: ScenarioStep?) {
        val executor = stepExecutors[step::class] as? StepExecutor<T>
            ?: error("No executor found for step type: ${step::class.simpleName}")

        val start = System.currentTimeMillis()
        executor.execute(step, context, nextStep)
        val duration = System.currentTimeMillis() - start

        logger.info { "Step ${context.currentStepIndex + 1} (${step::class.simpleName}) completed in $duration ms" }
    }

    /** Copies the fixture, writes `workspace.files` over it, and returns the cursor markers of those files. */
    private fun prepareWorkspace(
        definition: ScenarioDefinition,
    ): Pair<ScenarioWorkspace, Map<String, List<CursorMarker>>> {
        val config = definition.scenario.workspace
        val root = WorkspaceFixture.materialize(
            scenarioSource = java.nio.file.Path.of(definition.source),
            fixtureName = config?.fixture,
        )
        val markers = writeInlineFilesOrCleanUp(root, definition.scenario.name, config?.files.orEmpty())
        return ScenarioWorkspace(root) to markers
    }
}

private inline fun <T> recordPrimary(recorder: NamedObservationRecorder?, block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    recorder?.primaryFailure(failure)
    throw failure
}

private const val DEFAULT_SCENARIO_TIMEOUT_MS = 300_000L

internal fun hasNamedCheckMetadata(definition: ScenarioDefinition): Boolean =
    checks(definition).any { (_, check) -> check.id != null || check.xfail != null }

private fun checks(definition: ScenarioDefinition): List<Pair<ScenarioStep, JsonCheck>> =
    definition.scenario.steps.flatMap { step ->
        val checks = when (step) {
            is ScenarioStep.Assert -> step.checks
            is ScenarioStep.WaitNotification -> step.checks
            is ScenarioStep.Completion -> step.checks
            is ScenarioStep.CodeAction -> step.checks
            is ScenarioStep.Formatting -> step.checks
            is ScenarioStep.Rename -> step.checks
            is ScenarioStep.CliCommand -> step.checks
            else -> emptyList()
        }
        checks.map { step to it }
    }

/**
 * Writes [files] into the materialized workspace at [root]. On any failure the workspace is deleted, since the
 * scenario never starts and nothing else would clean it up; invalid entries are reported with [scenarioName].
 */
internal fun writeInlineFilesOrCleanUp(
    root: java.nio.file.Path,
    scenarioName: String,
    files: Map<String, String>,
): Map<String, List<CursorMarker>> {
    var written = false
    return AutoCloseable { if (!written) WorkspaceFixture.cleanup(root) }.use {
        val markers = try {
            InlineFiles.write(root, files)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Scenario '$scenarioName': ${failure.message}", failure)
        }
        written = true
        markers
    }
}
