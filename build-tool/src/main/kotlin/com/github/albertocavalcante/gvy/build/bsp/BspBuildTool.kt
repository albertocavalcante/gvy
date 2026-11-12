// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.bsp

import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.SourcesResult
import com.github.albertocavalcante.gvy.build.BspCompatibleBuildTool
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * BSP (Build Server Protocol) build tool for Bazel, sbt, and Mill.
 *
 * Connects to BSP servers that advertise themselves via .bsp directory JSON files.
 * This enables support for build tools without direct API implementations.
 */
class BspBuildTool internal constructor(private val clientFactory: (BspConnectionDetails, Path) -> BspClient) :
    BspCompatibleBuildTool {

    constructor() : this(::BspClient)

    private val logger = KotlinLogging.logger {}

    private companion object {
        private const val MAVEN_DATA_KIND = "maven"
        private const val ARTIFACTS_FIELD = "artifacts"
        private const val URI_FIELD = "uri"
    }

    override val name: String = "BSP"

    override fun canHandle(workspaceRoot: Path): Boolean {
        val bspDir = workspaceRoot.resolve(".bsp")
        if (!bspDir.exists()) return false
        return BspConnectionDetails.findConnectionFiles(workspaceRoot).isNotEmpty()
    }

    override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution {
        val connection = BspConnectionDetails.findFirst(workspaceRoot) ?: run {
            val message = "No BSP connection found in ${workspaceRoot.resolve(".bsp")}"
            logger.warn { message }
            return WorkspaceResolution.failed("BSP_CONNECTION_NOT_FOUND", message)
        }

        logger.info { "Resolving dependencies via BSP server: ${connection.name}" }
        onProgress?.invoke("Connecting to ${connection.name}...")

        return clientFactory(connection, workspaceRoot).use { client ->
            if (!client.connect(onProgress)) {
                val message = "Failed to connect to BSP server ${connection.name}"
                logger.error { message }
                return@use WorkspaceResolution.failed("BSP_CONNECTION_FAILED", message)
            }

            onProgress?.invoke("Fetching build targets...")
            val targets = client.workspaceBuildTargets()
            if (targets == null) {
                val message = "Failed to discover targets from BSP server ${connection.name}"
                logger.warn { message }
                return@use WorkspaceResolution.failed("BSP_TARGET_DISCOVERY_FAILED", message)
            }
            if (targets.targets.isEmpty()) {
                logger.warn { "No build targets found from BSP server" }
                return@use WorkspaceResolution.empty()
            }

            val targetIds = targets.targets.map { it.id }
            logger.info { "Found ${targetIds.size} build targets" }

            // TODO(#1859): Report incomplete source and dependency metadata explicitly.
            //   See: https://github.com/albertocavalcante/gvy/issues/1859
            onProgress?.invoke("Fetching source directories...")
            val sources = client.buildTargetSources(targetIds)
            val sourceDirectories = extractSourceDirectories(sources)

            onProgress?.invoke("Fetching dependencies...")
            val deps = client.buildTargetDependencyModules(targetIds)
            val dependencies = extractDependencies(deps)

            logger.info { "BSP resolution complete: ${dependencies.size} deps, ${sourceDirectories.size} sources" }
            onProgress?.invoke("${connection.name}: ${dependencies.size} dependencies loaded")

            WorkspaceResolution(dependencies, sourceDirectories)
        }
    }

    private fun extractSourceDirectories(result: SourcesResult?): List<Path> = result?.items
        ?.flatMap { it.sources }
        ?.mapNotNull { source -> uriToPath(source.uri) }
        ?.filter { it.exists() }
        ?.distinct()
        ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun extractDependencies(result: DependencyModulesResult?): List<Path> = result?.items
        ?.flatMap { it.modules }
        ?.flatMap { module ->
            if (module.dataKind != MAVEN_DATA_KIND) {
                logger.debug { "Skipping dependency module of kind '${module.dataKind}', expected '$MAVEN_DATA_KIND'" }
                return@flatMap emptyList<String>()
            }
            val data = module.data as? Map<String, Any> ?: return@flatMap emptyList()
            val artifacts = data[ARTIFACTS_FIELD] as? List<Map<String, Any>> ?: return@flatMap emptyList()
            artifacts.mapNotNull { it[URI_FIELD] as? String }
        }
        ?.mapNotNull { uri -> uriToPath(uri) }
        ?.filter { it.exists() && it.toString().endsWith(".jar") }
        ?.distinct()
        ?: emptyList()

    private fun uriToPath(uri: String): Path? = runCatching {
        Paths.get(URI.create(uri))
    }.onFailure {
        logger.debug { "Could not resolve URI: $uri" }
    }.getOrNull()
}
