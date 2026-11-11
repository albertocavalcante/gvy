package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

class ScenarioExecutor(private val sessionFactory: LanguageServerSessionFactory) {
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
        val (workspace, inlineMarkers) = prepareWorkspace(definition)
        val scenario = definition.scenario

        val session = sessionFactory.start(scenario.server, scenario.name)

        try {
            val context = ScenarioContext(
                definition = definition,
                session = session,
                workspace = workspace,
                json = json,
                jsonPathConfig = jsonPathConfig,
                timeoutCapMs = scenario.xfail?.let { XfailOutcome.STEP_TIMEOUT_CAP_MS },
            )
            context.registerBuiltInVariables()
            inlineMarkers.forEach { (path, markers) ->
                context.registerCursors(workspace.resolveUri(path), markers)
            }

            scenario.steps.forEachIndexed { index, step ->
                logger.info { "Running step ${index + 1} (${step::class.simpleName}) for scenario '${scenario.name}'" }
                context.currentStepIndex = index
                context.totalSteps = scenario.steps.size
                val nextStep = scenario.steps.getOrNull(index + 1)

                executeStep(step, context, nextStep)
            }
        } finally {
            session.close()
            workspace.cleanup()
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

/**
 * Writes [files] into the materialized workspace at [root]. On any failure the workspace is deleted, since the
 * scenario never starts and nothing else would clean it up; invalid entries are reported with [scenarioName].
 */
internal fun writeInlineFilesOrCleanUp(
    root: java.nio.file.Path,
    scenarioName: String,
    files: Map<String, String>,
): Map<String, List<CursorMarker>> = runCatching { InlineFiles.write(root, files) }.getOrElse { failure ->
    WorkspaceFixture.cleanup(root)
    throw if (failure is IllegalArgumentException) {
        IllegalArgumentException("Scenario '$scenarioName': ${failure.message}", failure)
    } else {
        failure
    }
}
