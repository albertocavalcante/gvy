package com.github.albertocavalcante.groovylsp.e2e

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
        val workspace = prepareWorkspace(definition)
        val scenario = definition.scenario

        val session = sessionFactory.start(scenario.server, scenario.name)

        try {
            val context = ScenarioContext(
                definition = definition,
                session = session,
                workspace = workspace,
                json = json,
                jsonPathConfig = jsonPathConfig,
            )
            context.registerBuiltInVariables()

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

    private fun prepareWorkspace(definition: ScenarioDefinition): ScenarioWorkspace = ScenarioWorkspace(
        WorkspaceFixture.materialize(
            scenarioSource = java.nio.file.Path.of(definition.source),
            fixtureName = definition.scenario.workspace?.fixture,
        ),
    )
}
