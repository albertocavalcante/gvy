package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.coverage.GetCoverageParams
import com.github.albertocavalcante.groovylsp.providers.testing.parsers.SurefireXmlParser
import com.github.albertocavalcante.reports.coverage.model.CoverageResponse
import com.github.albertocavalcante.reports.coverage.parsers.JacocoParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Handles test-related custom LSP requests.
 */
class TestRequestDelegate(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val buildToolManagerProvider: () -> BuildToolManager?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = KotlinLogging.logger {}

    fun discoverTests(params: DiscoverTestsParams): CompletableFuture<List<TestSuite>> {
        logger.info { "Received groovy/discoverTests request for: ${params.workspaceUri}" }

        return coroutineScope.future {
            withContext(ioDispatcher) {
                val provider = TestDiscoveryProvider(compilationService)
                val result = provider.discoverTests(params.workspaceUri)
                logger.info { "discoverTests returning ${result.size} test suites: ${result.map { it.suite }}" }
                result
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand> {
        logger.info { "Received groovy/runTest request for suite: ${params.suite}, test: ${params.test}" }

        return CompletableFuture.supplyAsync {
            try {
                generateTestCommand(params)
            } catch (e: ResponseErrorException) {
                logger.error(e) { "Error generating test command" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error generating test command" }
                throw ResponseErrorException(
                    ResponseError(
                        ResponseErrorCode.InternalError,
                        "Failed to generate test command: ${e.message}",
                        null,
                    ),
                )
            }
        }
    }

    @Suppress("ThrowsCount")
    private fun generateTestCommand(params: RunTestParams): TestCommand {
        val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
            ?: throw createError(ResponseErrorCode.InvalidParams, "No workspace root found for: ${params.uri}")

        val buildToolManager = buildToolManagerProvider()
            ?: throw createError(ResponseErrorCode.InternalError, "Build tool manager not initialized")

        val buildTool = buildToolManager.detectBuildTool(workspaceRoot)
            ?: throw createError(
                ResponseErrorCode.InternalError,
                "No build tool detected for workspace: $workspaceRoot",
            )

        return buildTool.getTestCommand(
            workspaceRoot = workspaceRoot,
            suite = params.suite,
            test = params.test,
            debug = params.debug,
        ) ?: throw createError(
            ResponseErrorCode.InternalError,
            "Build tool '${buildTool.name}' does not support test execution.",
        )
    }

    /**
     * Returns information about the detected build tool.
     */
    fun getBuildToolInfo(params: GetBuildToolInfoParams): CompletableFuture<BuildToolInfo> {
        logger.info { "Received groovy/getBuildToolInfo request for: ${params.workspaceUri}" }

        return CompletableFuture.supplyAsync {
            val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
                ?: return@supplyAsync BuildToolInfo(name = "unknown", detected = false).also {
                    logger.info { "No workspace root found, returning unknown build tool" }
                }

            val buildToolManager = buildToolManagerProvider()
                ?: return@supplyAsync BuildToolInfo(name = "unknown", detected = false).also {
                    logger.info { "Build tool manager not initialized, returning unknown" }
                }

            val buildTool = buildToolManager.detectBuildTool(workspaceRoot)
                ?: return@supplyAsync BuildToolInfo(name = "unknown", detected = false).also {
                    logger.info { "No build tool detected for workspace: $workspaceRoot" }
                }

            // Check capabilities by probing with test commands
            val supportsTestExecution = buildTool.getTestCommand(
                workspaceRoot = workspaceRoot,
                suite = "com.example.Test",
                test = "test",
                debug = false,
            ) != null

            val supportsDebug = buildTool.getTestCommand(
                workspaceRoot = workspaceRoot,
                suite = "com.example.Test",
                test = "test",
                debug = true,
            ) != null

            // Probe for coverage support dynamically
            val supportsCoverage = buildTool.getCoverageCommand(
                workspaceRoot = workspaceRoot,
                suite = "com.example.Test",
                test = "test",
            ) != null

            logger.info {
                "Detected build tool: ${buildTool.name}, " +
                    "supportsTestExecution: $supportsTestExecution, supportsDebug: $supportsDebug, " +
                    "supportsCoverage: $supportsCoverage"
            }

            BuildToolInfo(
                name = buildTool.name.lowercase(),
                detected = true,
                supportsTestExecution = supportsTestExecution,
                supportsDebug = supportsDebug,
                supportsCoverage = supportsCoverage,
            )
        }
    }

    /**
     * Returns parsed test results from Surefire/Failsafe XML reports.
     */
    fun getTestResults(params: GetTestResultsParams): CompletableFuture<TestResultsResponse> {
        logger.info { "Received groovy/getTestResults request for: ${params.workspaceUri}" }

        return coroutineScope.future {
            withContext(ioDispatcher) {
                val workspaceRoot = resolveWorkspaceRoot(params.workspaceUri)
                SurefireXmlParser.parseWorkspace(workspaceRoot)
            }
        }
    }

    /**
     * Returns parsed coverage data from JaCoCo XML reports.
     */
    fun getCoverage(params: GetCoverageParams): CompletableFuture<CoverageResponse> {
        logger.info { "Received groovy/getCoverage request for: ${params.workspaceUri}" }

        return coroutineScope.future {
            withContext(ioDispatcher) {
                val workspaceRoot = resolveWorkspaceRoot(params.workspaceUri)
                JacocoParser.parseWorkspace(workspaceRoot)
            }
        }
    }

    /**
     * Resolve workspace URI to a File.
     */
    private fun resolveWorkspaceRoot(workspaceUri: String): File = if (workspaceUri.startsWith("file://")) {
        File(URI(workspaceUri))
    } else {
        File(workspaceUri)
    }

    private fun createError(code: ResponseErrorCode, message: String): ResponseErrorException =
        ResponseErrorException(ResponseError(code, message, null))
}
