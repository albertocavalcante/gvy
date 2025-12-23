package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Handles test-related custom LSP requests.
 */
class TestRequestDelegate(
    private val compilationService: GroovyCompilationService,
    private val buildToolManagerProvider: () -> BuildToolManager?,
) {
    private val logger = LoggerFactory.getLogger(TestRequestDelegate::class.java)

    fun discoverTests(params: DiscoverTestsParams): CompletableFuture<List<TestSuite>> {
        logger.info("Received groovy/discoverTests request for: ${params.workspaceUri}")

        return CompletableFuture.supplyAsync {
            val provider = TestDiscoveryProvider(compilationService)
            provider.discoverTests(params.workspaceUri)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand> {
        logger.info("Received groovy/runTest request for suite: ${params.suite}, test: ${params.test}")

        return CompletableFuture.supplyAsync {
            try {
                generateTestCommand(params)
            } catch (e: ResponseErrorException) {
                logger.error("Error generating test command", e)
                throw e
            } catch (e: Exception) {
                logger.error("Error generating test command", e)
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

    private fun createError(code: ResponseErrorCode, message: String): ResponseErrorException =
        ResponseErrorException(ResponseError(code, message, null))
}
