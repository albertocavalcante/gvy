// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.dependencies

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.DependencyMetadata
import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.future.future
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
                val metadata = getMetadataCancellable(buildTool, workspaceRoot)

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

    /**
     * Runs the blocking metadata fetch, signalling the build tool as soon as the request's job starts cancelling
     * (for example an LSP `$/cancelRequest`), so it stops the in-flight operation and releases its lease instead of
     * running to completion.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun getMetadataCancellable(buildTool: BuildTool, workspaceRoot: Path): List<DependencyMetadata>? {
        val cancellation = ResolutionCancellation()
        val registration = currentCoroutineContext().job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause -> if (cause != null) cancellation.cancel() }
        try {
            return buildTool.getDependencyMetadata(workspaceRoot, cancellation)
        } finally {
            registration.dispose()
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
