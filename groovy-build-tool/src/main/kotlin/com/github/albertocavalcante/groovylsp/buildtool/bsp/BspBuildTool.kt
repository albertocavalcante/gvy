package com.github.albertocavalcante.groovylsp.buildtool.bsp

import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.SourcesResult
import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import org.slf4j.LoggerFactory
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
class BspBuildTool : BuildTool {

    private val logger = LoggerFactory.getLogger(BspBuildTool::class.java)

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
        val connection = BspConnectionDetails.findFirst(workspaceRoot)
            ?: return WorkspaceResolution.empty().also {
                logger.warn("No BSP connection found in ${workspaceRoot.resolve(".bsp")}")
            }

        logger.info("Resolving dependencies via BSP server: ${connection.name}")
        onProgress?.invoke("Connecting to ${connection.name}...")

        return BspClient(connection, workspaceRoot).use { client ->
            if (!client.connect(onProgress)) {
                logger.error("Failed to connect to BSP server ${connection.name}")
                return@use WorkspaceResolution.empty()
            }

            onProgress?.invoke("Fetching build targets...")
            val targets = client.workspaceBuildTargets()
            if (targets == null || targets.targets.isEmpty()) {
                logger.warn("No build targets found from BSP server")
                return@use WorkspaceResolution.empty()
            }

            val targetIds = targets.targets.map { it.id }
            logger.info("Found ${targetIds.size} build targets")

            onProgress?.invoke("Fetching source directories...")
            val sources = client.buildTargetSources(targetIds)
            val sourceDirectories = extractSourceDirectories(sources)

            onProgress?.invoke("Fetching dependencies...")
            val deps = client.buildTargetDependencyModules(targetIds)
            val dependencies = extractDependencies(deps)

            logger.info("BSP resolution complete: ${dependencies.size} deps, ${sourceDirectories.size} sources")
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
                logger.debug("Skipping dependency module of kind '{}', expected '{}'", module.dataKind, MAVEN_DATA_KIND)
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
        logger.debug("Could not resolve URI: $uri")
    }.getOrNull()
}
