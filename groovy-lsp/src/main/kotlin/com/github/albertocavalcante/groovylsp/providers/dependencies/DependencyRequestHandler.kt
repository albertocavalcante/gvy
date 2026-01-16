package com.github.albertocavalcante.groovylsp.providers.dependencies

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleConnectionFactory
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleConnectionPool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.exists

/**
 * Handles dependency-related custom LSP requests.
 */
class DependencyRequestHandler(
    private val coroutineScope: CoroutineScope,
    private val buildToolManagerProvider: () -> BuildToolManager?,
    private val workspaceRootProvider: () -> Path?,
    private val connectionFactory: GradleConnectionFactory = GradleConnectionPool,
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
                    ?: return@withContext DependenciesResult(
                        dependencies = emptyList(),
                        buildTool = "unknown",
                    ).also {
                        logger.warn { "No workspace root found for: ${params.workspaceUri}" }
                    }

                val buildToolManager = buildToolManagerProvider()
                    ?: return@withContext DependenciesResult(
                        dependencies = emptyList(),
                        buildTool = "unknown",
                    ).also {
                        logger.warn { "Build tool manager not initialized" }
                    }

                val buildTool = buildToolManager.detectBuildTool(workspaceRoot)
                    ?: return@withContext DependenciesResult(
                        dependencies = emptyList(),
                        buildTool = "unknown",
                    ).also {
                        logger.warn { "No build tool detected for workspace: $workspaceRoot" }
                    }

                val buildToolName = buildTool.name.lowercase()
                logger.info { "Detected build tool: $buildToolName" }

                // Currently only supporting Gradle
                val dependencies = when (buildTool) {
                    is GradleBuildTool -> extractGradleDependencies(workspaceRoot)
                    else -> {
                        logger.warn { "Build tool '$buildToolName' is not yet supported for dependency extraction" }
                        emptyList()
                    }
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
     * Extracts dependencies from a Gradle project using the Tooling API.
     */
    private fun extractGradleDependencies(workspaceRoot: Path): List<DependencyInfo> {
        logger.info { "Extracting dependencies from Gradle project: $workspaceRoot" }

        return try {
            val connection = connectionFactory.getConnection(workspaceRoot, null)
            val ideaProject = connection.model(IdeaProject::class.java).get()

            val dependencies = mutableListOf<DependencyInfo>()

            ideaProject.modules.forEach { module ->
                logger.debug { "Processing module: ${module.name}" }

                module.dependencies
                    .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                    .forEach { dependency ->
                        val jarPath = dependency.file.toPath()
                        if (jarPath.exists()) {
                            val depInfo = extractDependencyInfo(dependency, jarPath)
                            dependencies.add(depInfo)
                            logger.debug { "Found dependency: ${depInfo.name}" }
                        } else {
                            logger.warn { "Dependency JAR not found: $jarPath" }
                        }
                    }
            }

            logger.info { "Extracted ${dependencies.size} dependencies from Gradle project" }
            dependencies.distinctBy { it.path } // Remove duplicates across modules
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract Gradle dependencies: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Extracts information from an IdeaSingleEntryLibraryDependency.
     */
    private fun extractDependencyInfo(dependency: IdeaSingleEntryLibraryDependency, jarPath: Path): DependencyInfo {
        // Try to extract name and version from the JAR file name
        // Format is typically: name-version.jar (e.g., commons-lang3-3.12.0.jar)
        val fileName = jarPath.fileName.toString()
        val (name, version) = parseJarFileName(fileName)

        // Try to get scope from the dependency
        // The IdeaDependency interface has a scope property
        val scope = try {
            dependency.scope?.scope?.lowercase() ?: "compile"
        } catch (e: Exception) {
            logger.debug { "Could not extract scope from dependency: ${e.message}" }
            "compile"
        }

        // Convert scope to standardized values
        val normalizedScope = when (scope) {
            "compile", "implementation", "api" -> "compile"
            "runtime", "runtimeonly" -> "runtime"
            "test", "testimplementation", "testcompile" -> "test"
            "provided", "compileonly" -> "provided"
            else -> scope
        }

        // For now, we cannot reliably determine if a dependency is transitive from the Gradle model
        // This would require additional resolution data. Setting to true (most are transitive).
        val isTransitive = true

        return DependencyInfo(
            name = name,
            version = version,
            scope = normalizedScope,
            path = jarPath.toUri().toString(),
            isTransitive = isTransitive,
        )
    }

    /**
     * Parses a JAR file name to extract the name and version.
     * Examples:
     * - commons-lang3-3.12.0.jar -> ("commons-lang3", "3.12.0")
     * - groovy-all-2.5.14.jar -> ("groovy-all", "2.5.14")
     * - junit-4.13.jar -> ("junit", "4.13")
     */
    private fun parseJarFileName(fileName: String): Pair<String, String> {
        // Remove .jar extension
        val baseName = fileName.removeSuffix(".jar")

        // Try to find the last occurrence of a version pattern (e.g., -3.12.0, -2.5.14)
        // Version pattern: dash followed by digits
        val versionRegex = Regex("(.+?)-(\\d+[.\\d]*.*?)$")
        val match = versionRegex.find(baseName)

        return if (match != null) {
            val name = match.groupValues[1]
            val version = match.groupValues[2]
            Pair(name, version)
        } else {
            // Could not parse version, return the whole name
            Pair(baseName, "unknown")
        }
    }

    /**
     * Resolve workspace URI to a File.
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
