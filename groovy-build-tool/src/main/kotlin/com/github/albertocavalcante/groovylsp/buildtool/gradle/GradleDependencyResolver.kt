package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.DependencyResolver
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Programmatic Gradle dependency resolver using the Gradle Tooling API.
 *
 * This implements:
 * - In-process dependency resolution (no subprocess)
 * - Automatic retry with isolated Gradle user home on init script failures
 * - Exponential backoff retry for transient failures (e.g., lock timeouts)
 * - Source directory extraction from IdeaProject model
 */
class GradleDependencyResolver(
    private val connectionFactory: GradleConnectionFactory = GradleConnectionPool,
    private val compatibilityService: GradleCompatibilityService = GradleCompatibilityService(),
    private val failureAnalyzer: GradleFailureAnalyzer = GradleFailureAnalyzer(),
    private val javaHome: Path? = null,
    private val retryConfig: RetryConfig = RetryConfig(),
) : DependencyResolver {

    /**
     * Configuration for retry behavior on transient failures.
     * Made a data class for injectable testability with zero-delay test configs.
     *
     * Default wait times: 2s + 4s + 8s = 14 seconds total before giving up.
     */
    data class RetryConfig(
        val maxAttempts: Int = 4,
        val initialDelayMs: Long = 2000L,
        val backoffMultiplier: Double = 2.0,
    )

    private val logger = LoggerFactory.getLogger(GradleDependencyResolver::class.java)

    override val name: String = "Gradle Tooling API"

    /**
     * Resolves all binary JAR dependencies from a Gradle project.
     *
     * @param projectFile Path to the project directory (build.gradle location)
     * @return List of paths to dependency JAR files
     */
    override fun resolveDependencies(projectFile: Path): List<Path> =
        resolveWithSourceDirectories(projectFile).dependencies

    /**
     * Resolves both dependencies and source directories from a Gradle project.
     * This extracts source directories from the IdeaProject model, supporting
     * custom source directory layouts.
     *
     * Implements resilient resolution with:
     * - Exponential backoff retry for transient failures (e.g., lock timeouts)
     * - Isolated Gradle user home fallback for init script errors
     *
     * @param projectFile Path to the project directory (build.gradle location)
     * @return WorkspaceResolution containing both dependencies and source directories
     */
    fun resolveWithSourceDirectories(projectFile: Path): WorkspaceResolution {
        val projectDir = if (Files.isDirectory(projectFile)) projectFile else projectFile.parent

        logger.info("Resolving dependencies using Gradle Tooling API for: $projectDir")

        // First, try with exponential backoff for transient failures
        val transientRetryResult = runWithTransientRetry(projectDir)
        if (transientRetryResult.isSuccess) {
            return transientRetryResult.getOrThrow()
        }

        val lastException = transientRetryResult.exceptionOrNull()

        // Log the original failure before attempting fallback
        if (lastException != null) {
            logger.error("Initial Gradle resolution failed: ${lastException.message}", lastException)
        }

        // Fall back to isolated Gradle user home for init script errors
        if (lastException != null && failureAnalyzer.isInitScriptError(lastException)) {
            val isolatedResult = tryWithIsolatedUserHome(projectDir)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        logger.error("Gradle dependency resolution failed: ${lastException?.message}", lastException)
        return WorkspaceResolution(emptyList(), emptyList())
    }

    /**
     * Attempts resolution with exponential backoff for transient failures.
     * Returns the result of the last attempt (success or failure).
     */
    @Suppress("LoopWithTooManyJumpStatements") // Retry loop with early exit is clearer than alternatives
    private fun runWithTransientRetry(projectDir: Path): Result<WorkspaceResolution> {
        var lastResult: Result<WorkspaceResolution> = Result.failure(IllegalStateException("No attempts made"))
        var currentDelay = retryConfig.initialDelayMs

        for (attempt in 1..retryConfig.maxAttempts) {
            lastResult = runCatching {
                resolveWithGradleUserHome(projectDir, gradleUserHomeDir = null)
            }

            if (lastResult.isSuccess) {
                if (attempt > 1) {
                    logger.info("Gradle dependency resolution succeeded on attempt $attempt")
                }
                return lastResult
            }

            val exception = lastResult.exceptionOrNull() ?: break

            // Only retry if this is a transient failure and we have attempts remaining
            if (!failureAnalyzer.isTransient(exception) || attempt >= retryConfig.maxAttempts) {
                break
            }

            logger.warn(
                "Gradle dependency resolution failed (attempt $attempt/${retryConfig.maxAttempts}): " +
                    "${exception.message}. Retrying in ${currentDelay}ms...",
            )
            Thread.sleep(currentDelay)
            currentDelay = (currentDelay * retryConfig.backoffMultiplier).toLong()
        }

        return lastResult
    }

    /**
     * Tries resolution with an isolated Gradle user home to avoid init script issues.
     */
    private fun tryWithIsolatedUserHome(projectDir: Path): WorkspaceResolution? {
        logger.warn(
            "Gradle dependency resolution failed; retrying with isolated Gradle user home " +
                "to avoid incompatible user init scripts",
        )

        val isolatedUserHome = isolatedGradleUserHomeDir()
        return runCatching {
            resolveWithGradleUserHome(projectDir, isolatedUserHome.toFile())
        }.onFailure { e ->
            logger.error("Isolated Gradle user home retry also failed: ${e.message}", e)
        }.getOrNull()
    }

    /**
     * Returns the Gradle user home directory path.
     */
    override fun resolveLocalRepository(): Path? {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME")
        if (!gradleUserHome.isNullOrBlank()) {
            val path = Paths.get(gradleUserHome)
            if (Files.exists(path)) {
                return path
            }
        }

        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".gradle")
    }

    private fun resolveWithGradleUserHome(projectDir: Path, gradleUserHomeDir: File?): WorkspaceResolution {
        val connection = connectionFactory.getConnection(projectDir, gradleUserHomeDir)

        // Pre-flight check: validate JDK/Gradle compatibility before attempting model fetch
        checkJdkGradleCompatibility(connection)

        val modelBuilder = connection.model(IdeaProject::class.java)
            .withArguments(
                "-Dorg.gradle.daemon=true",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.configureondemand=true",
                "-Dorg.gradle.vfs.watch=true",
            )
            .setJvmArguments("-Xmx1g", "-XX:+UseG1GC")

        // Inject configured JAVA_HOME if available
        if (javaHome != null) {
            logger.info("Setting Java home for Gradle Tooling operation: $javaHome")
            modelBuilder.setJavaHome(javaHome.toFile())
        }

        val ideaProject = modelBuilder.get()

        val dependencies = mutableSetOf<Path>()
        val sourceDirectories = mutableSetOf<Path>()

        ideaProject.modules.forEach { module ->
            processModule(module, dependencies, sourceDirectories)
        }

        val depCount = dependencies.size
        val srcCount = sourceDirectories.size
        logger.info("Resolved $depCount dependencies and $srcCount source directories via Gradle Tooling API")
        return WorkspaceResolution(dependencies.toList(), sourceDirectories.toList())
    }

    /**
     * Pre-flight check for JDK/Gradle compatibility.
     *
     * Uses BuildEnvironment model which is safe to fetch - it does NOT trigger
     * build script compilation, so it works even when there's a JDK/Gradle mismatch.
     *
     * If incompatible, throws a descriptive exception instead of proceeding to
     * fail later with a confusing "Unsupported class file major version" error.
     */
    private fun checkJdkGradleCompatibility(connection: ProjectConnection) {
        val gradleVersion = runCatching {
            connection.getModel(BuildEnvironment::class.java).gradle.gradleVersion
        }.getOrNull() ?: return // If we can't get version, proceed and let it fail naturally

        // If javaHome is configured, we should check compatibility for THAT JDK.
        // If not, use the current runtime JDK.
        val jdkMajor = if (javaHome != null) {
            captureJavaMajorVersion(javaHome)
        } else {
            Runtime.version().feature()
        } ?: Runtime.version().feature()

        if (!compatibilityService.isCompatible(gradleVersion, jdkMajor)) {
            val suggestion = compatibilityService.suggestFix(gradleVersion, jdkMajor)
            logger.error(suggestion)
            throw GradleJdkIncompatibleException(
                suggestion ?: "JDK $jdkMajor is incompatible with Gradle $gradleVersion",
            )
        }

        logger.debug("JDK/Gradle compatibility check passed: Gradle $gradleVersion with JDK $jdkMajor")
    }

    /**
     * Captures the major version of a JDK at the given path.
     * Stubs for now, could be improved by running `java -version`.
     */
    private fun captureJavaMajorVersion(javaHome: Path): Int? {
        // Highly simplified: assuming the path ends in something like "jdk-21.jdk" or "java-17"
        // for better results we'd need to execute bin/java -version or check release file.
        val releaseFile = javaHome.resolve("release")
        if (Files.exists(releaseFile)) {
            try {
                val content = Files.readString(releaseFile)
                val versionLine = content.lines().find { it.startsWith("JAVA_VERSION=") }
                if (versionLine != null) {
                    val version = versionLine.removePrefix("JAVA_VERSION=").trim('"')
                    val major = version.split(".").firstOrNull() ?: version
                    return major.toIntOrNull()
                }
            } catch (e: Exception) {
                logger.warn("Failed to read release file in $javaHome", e)
            }
        }
        return null
    }

    private fun isolatedGradleUserHomeDir(): Path {
        val userHome = System.getProperty("user.home")
        val base = if (!userHome.isNullOrBlank()) {
            Paths.get(userHome)
        } else {
            Paths.get(System.getProperty("java.io.tmpdir"))
        }

        val dir = base.resolve(".groovy-lsp").resolve("gradle-user-home")
        return runCatching { Files.createDirectories(dir) }
            .getOrElse { e ->
                logger.error("Failed to create isolated Gradle user home dir at $dir; falling back to temp dir", e)
                val tempDir = Paths.get(System.getProperty("java.io.tmpdir"))
                    .resolve("groovy-lsp-gradle-user-home")
                Files.createDirectories(tempDir)
                tempDir
            }
    }

    private fun processModule(
        module: IdeaModule,
        dependencies: MutableSet<Path>,
        sourceDirectories: MutableSet<Path>,
    ) {
        logger.debug("Processing module: ${module.name}")

        // Extract dependencies
        module.dependencies
            .filterIsInstance<IdeaSingleEntryLibraryDependency>()
            .forEach { dependency ->
                val jarPath = dependency.file.toPath()
                if (jarPath.exists()) {
                    logger.debug("Found dependency: ${dependency.file.name}")
                    dependencies.add(jarPath)
                } else {
                    logger.warn("Dependency JAR not found: $jarPath")
                }
            }

        // Extract source directories from IdeaProject model
        module.contentRoots?.forEach { root ->
            root.sourceDirectories?.forEach { dir ->
                dir.directory?.toPath()?.takeIf { it.exists() }?.let(sourceDirectories::add)
            }
            root.testDirectories?.forEach { dir ->
                dir.directory?.toPath()?.takeIf { it.exists() }?.let(sourceDirectories::add)
            }
        }
    }
}
