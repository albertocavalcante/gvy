package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.BuildExecutableResolver
import com.github.albertocavalcante.groovylsp.buildtool.DependencyMetadata
import com.github.albertocavalcante.groovylsp.buildtool.NativeGradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Gradle build tool that uses the Gradle Tooling API to extract
 * binary JAR dependencies from a project.
 *
 * This is Phase 1 implementation - focuses on getting dependencies
 * on the classpath for compilation. Future phases will add source
 * JAR support and on-demand downloading.
 */
class GradleBuildTool(
    private val connectionFactory: GradleConnectionFactory = GradleConnectionPool,
    private val compatibilityService: GradleCompatibilityService = GradleCompatibilityService(),
    private val failureAnalyzer: GradleFailureAnalyzer = GradleFailureAnalyzer(),
    private val javaHome: Path? = null,
    retryConfig: GradleDependencyResolver.RetryConfig = GradleDependencyResolver.RetryConfig(),
) : NativeGradleBuildTool {

    private val logger = KotlinLogging.logger {}
    private val dependencyResolver = GradleDependencyResolver(
        connectionFactory = connectionFactory,
        compatibilityService = compatibilityService,
        failureAnalyzer = failureAnalyzer,
        javaHome = javaHome,
        retryConfig = retryConfig,
    )

    override val name: String = "Gradle"

    /**
     * Checks if the given directory is a Gradle project.
     */
    override fun canHandle(workspaceRoot: Path): Boolean = GradleBuildFiles.fileNames.any { fileName ->
        val candidate = workspaceRoot.resolve(fileName)
        val present = candidate.exists()
        logger.debug { "Gradle probe: $candidate present=$present" }
        present
    }

    /**
     * Resolves all binary JAR dependencies and source directories from a Gradle project.
     * Source directories are extracted from the IdeaProject model, supporting custom layouts.
     *
     * @param workspaceRoot The root directory of the Gradle project
     * @param onProgress Optional callback for progress updates (e.g., Gradle distribution download)
     * @return WorkspaceResolution containing dependency JAR files and source directories
     */
    override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution {
        if (!canHandle(workspaceRoot)) {
            logger.info { "Not a Gradle project: $workspaceRoot" }
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.info { "Resolving Gradle dependencies for: $workspaceRoot" }

        // Delegate to resolver which extracts both dependencies and source directories
        // from the IdeaProject model (supports custom source directory layouts)
        val resolution = dependencyResolver.resolveWithSourceDirectories(workspaceRoot)

        val depCount = resolution.dependencies.size
        val srcCount = resolution.sourceDirectories.size
        logger.info { "Resolved $depCount dependencies and $srcCount source directories" }
        return resolution
    }

    override fun createWatcher(
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        onChange: (java.nio.file.Path) -> Unit,
    ): com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher =
        GradleBuildFileWatcher(coroutineScope, onChange)

    override fun getTestCommand(workspaceRoot: Path, suite: String, test: String?, debug: Boolean): TestCommand {
        val testFilter = if (test != null) "$suite.$test" else suite
        val args = mutableListOf("test", "--tests", testFilter)

        if (debug) {
            args.add("--debug-jvm")
        }

        return TestCommand(
            executable = BuildExecutableResolver.resolveGradle(workspaceRoot),
            args = args,
            cwd = workspaceRoot.toString(),
        )
    }

    override fun getCoverageCommand(workspaceRoot: Path, suite: String, test: String?): TestCommand {
        val testFilter = if (test != null) "$suite.$test" else suite
        val args = listOf("test", "--tests", testFilter, "jacocoTestReport")

        return TestCommand(
            executable = BuildExecutableResolver.resolveGradle(workspaceRoot),
            args = args,
            cwd = workspaceRoot.toString(),
        )
    }

    /**
     * Retrieves rich dependency metadata for all dependencies in the workspace.
     * This provides Maven coordinates, scope, and path information suitable for
     * UI display (e.g., VS Code dependency tree view).
     *
     * @param workspaceRoot The root directory of the Gradle project
     * @return List of dependency metadata, or empty list on failure
     */
    override fun getDependencyMetadata(workspaceRoot: Path): List<DependencyMetadata> {
        logger.info { "Extracting dependency metadata from Gradle project: $workspaceRoot" }

        return try {
            val connection = connectionFactory.getConnection(workspaceRoot, null)
            val ideaProject = connection.model(IdeaProject::class.java).get()

            val dependencies = mutableListOf<DependencyMetadata>()

            ideaProject.modules.forEach { module ->
                logger.debug { "Processing module: ${module.name}" }

                module.dependencies
                    .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                    .forEach { dependency ->
                        val jarPath = dependency.file.toPath()
                        if (jarPath.exists()) {
                            val depMetadata = extractDependencyMetadata(dependency, jarPath)
                            dependencies.add(depMetadata)
                            logger.debug { "Found dependency: ${depMetadata.name}" }
                        } else {
                            logger.warn { "Dependency JAR not found: $jarPath" }
                        }
                    }
            }

            logger.info { "Extracted ${dependencies.size} dependencies from Gradle project" }

            // Remove duplicates across modules and aggregate scopes if the same dependency appears with different scopes
            dependencies
                .groupBy { it.path }
                .map { (_, depsWithSamePath) ->
                    val first = depsWithSamePath.first()
                    val distinctScopes = depsWithSamePath.map { it.scope }.distinct()
                    val combinedScope = distinctScopes.joinToString(",")

                    if (combinedScope == first.scope) {
                        first
                    } else {
                        DependencyMetadata(
                            name = first.name,
                            version = first.version,
                            scope = combinedScope,
                            path = first.path,
                            isTransitive = first.isTransitive,
                        )
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract Gradle dependency metadata: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Extracts dependency metadata from an IdeaSingleEntryLibraryDependency.
     */
    private fun extractDependencyMetadata(
        dependency: IdeaSingleEntryLibraryDependency,
        jarPath: Path,
    ): DependencyMetadata {
        // Try to extract name and version from the JAR file name
        // Format is typically: name-version.jar (e.g., commons-lang3-3.12.0.jar)
        val fileName = jarPath.fileName.toString()
        val (parsedName, parsedVersion) = parseJarFileName(fileName)

        // Prefer Gradle's module coordinates when available
        val gradleModuleVersion = try {
            dependency.gradleModuleVersion
        } catch (e: Exception) {
            logger.debug { "Could not extract Gradle module version from dependency: ${e.message}" }
            null
        }

        val name: String
        val version: String

        if (gradleModuleVersion != null &&
            !gradleModuleVersion.group.isNullOrBlank() &&
            !gradleModuleVersion.name.isNullOrBlank()
        ) {
            // Use Maven-style coordinates: group:artifact
            name = "${gradleModuleVersion.group}:${gradleModuleVersion.name}"
            // Prefer the version from the Gradle model, fall back to the parsed one if necessary
            version = if (!gradleModuleVersion.version.isNullOrBlank()) {
                gradleModuleVersion.version
            } else {
                parsedVersion
            }
        } else {
            // Fall back to the artifact name and version parsed from the JAR filename
            name = parsedName
            version = parsedVersion
        }

        // Try to get scope from the dependency
        val scope = try {
            dependency.scope?.scope?.lowercase() ?: "compile"
        } catch (e: Exception) {
            logger.debug { "Could not extract scope from dependency: ${e.message}" }
            "compile"
        }

        // Normalize scope to standard values
        val normalizedScope = DependencyMetadata.normalizeScope(scope)

        // For now, we cannot reliably determine if a dependency is transitive from the Gradle model
        // This would require additional resolution data. Setting to false as a conservative default.
        val isTransitive = false

        return DependencyMetadata(
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
     * - slf4j-api-2.0.0-SNAPSHOT.jar -> ("slf4j-api", "2.0.0-SNAPSHOT")
     */
    private fun parseJarFileName(fileName: String): Pair<String, String> {
        // Remove .jar extension
        val baseName = fileName.removeSuffix(".jar")

        // Try to find the last occurrence of a version pattern (e.g., -3.12.0, -2.5.14, -1.0.0-SNAPSHOT)
        // Version pattern: dash followed by digits, then dots/digits and optional alphanumeric qualifiers
        val versionRegex = Regex("(.+?)-(\\d+[.\\d\\-+a-zA-Z]*)$")
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
}
