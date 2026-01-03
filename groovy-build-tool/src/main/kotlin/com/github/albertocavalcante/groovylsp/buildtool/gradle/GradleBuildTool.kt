package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.BuildExecutableResolver
import com.github.albertocavalcante.groovylsp.buildtool.NativeGradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(GradleBuildTool::class.java)
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
        logger.debug("Gradle probe: {} present={}", candidate, present)
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
            logger.info("Not a Gradle project: $workspaceRoot")
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.info("Resolving Gradle dependencies for: $workspaceRoot")

        // Delegate to resolver which extracts both dependencies and source directories
        // from the IdeaProject model (supports custom source directory layouts)
        val resolution = dependencyResolver.resolveWithSourceDirectories(workspaceRoot)

        val depCount = resolution.dependencies.size
        val srcCount = resolution.sourceDirectories.size
        logger.info("Resolved $depCount dependencies and $srcCount source directories")
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
}
