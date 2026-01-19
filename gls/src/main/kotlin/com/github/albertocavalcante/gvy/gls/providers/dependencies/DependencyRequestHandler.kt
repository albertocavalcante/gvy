package com.github.albertocavalcante.gvy.gls.providers.dependencies

import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.gls.async.future
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Handles dependency-related custom LSP requests.
 *
 * This handler delegates to the BuildTool abstraction layer for dependency
 * metadata extraction, maintaining clean separation of concerns.
 */
class DependencyRequestHandler(
    private val coroutineScope: CoroutineScope,
    private val buildToolManagerProvider: () -> BuildToolManager?,
    private val workspaceRootProvider: () -> Path?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Retrieves all dependencies for the workspace.
     */
    fun getDependencies(params: GetDependenciesParams): CompletableFuture<DependenciesResult> {
        logger.info { "Received groovy/workspace/dependencies request for: ${params.workspaceUri}" }

        return coroutineScope.future {
            withContext(ioDispatcher) {
                val workspaceRoot = resolveWorkspaceRoot(params.workspaceUri)
                    ?: workspaceRootProvider()
                    ?: return@withContext emptyResult("unknown").also {
                        logger.warn { "No workspace root found for: ${params.workspaceUri}" }
                    }

                val buildToolManager = buildToolManagerProvider()
                    ?: return@withContext emptyResult("unknown").also {
                        logger.warn { "Build tool manager not initialized" }
                    }

                val buildTool = buildToolManager.detectBuildTool(workspaceRoot)
                    ?: return@withContext emptyResult("unknown").also {
                        logger.warn { "No build tool detected for workspace: $workspaceRoot" }
                    }

                val buildToolName = buildTool.name.lowercase()
                logger.info { "Detected build tool: $buildToolName" }

                // Delegate to BuildTool for metadata extraction
                val metadata = buildTool.getDependencyMetadata(workspaceRoot)

                if (metadata == null) {
                    logger.warn { "Build tool '$buildToolName' does not support dependency metadata extraction" }
                    return@withContext emptyResult(buildToolName)
                }

                val dependencies = metadata.map { meta ->
                    DependencyInfo(
                        name = meta.name,
                        version = meta.version,
                        scope = meta.scope,
                        path = meta.path,
                        isTransitive = meta.isTransitive,
                    )
                }

                logger.info { "Returning ${dependencies.size} dependencies for workspace" }
                DependenciesResult(
                    dependencies = dependencies,
                    buildTool = buildToolName,
                )
            }
        }
    }

    private fun emptyResult(buildTool: String) = DependenciesResult(
        dependencies = emptyList(),
        buildTool = buildTool,
    )

    /**
     * Resolve workspace URI to a Path.
     */
    private fun resolveWorkspaceRoot(workspaceUri: String): Path? = try {
        if (workspaceUri.startsWith("file://")) {
            File(URI(workspaceUri)).toPath()
        } else {
            File(workspaceUri).toPath()
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to resolve workspace URI: $workspaceUri" }
        null
    }
}
