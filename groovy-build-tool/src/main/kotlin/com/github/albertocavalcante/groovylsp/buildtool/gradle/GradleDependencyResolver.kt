package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.DependencyResolver
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
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
 * - Source directory extraction from IdeaProject model
 */
class GradleDependencyResolver(private val connectionFactory: GradleConnectionFactory = GradleConnectionPool) :
    DependencyResolver {

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
     * @param projectFile Path to the project directory (build.gradle location)
     * @return WorkspaceResolution containing both dependencies and source directories
     */
    fun resolveWithSourceDirectories(projectFile: Path): WorkspaceResolution {
        val projectDir = if (Files.isDirectory(projectFile)) projectFile else projectFile.parent

        logger.info("Resolving dependencies using Gradle Tooling API for: $projectDir")

        val defaultAttempt = runCatching {
            resolveWithGradleUserHome(projectDir, gradleUserHomeDir = null)
        }

        if (defaultAttempt.isSuccess) {
            return defaultAttempt.getOrThrow()
        }

        val failure = defaultAttempt.exceptionOrNull() ?: return WorkspaceResolution(emptyList(), emptyList())
        if (!shouldRetryWithIsolatedGradleUserHome(failure)) {
            logger.error("Gradle dependency resolution failed: ${failure.message}", failure)
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.warn(
            "Gradle dependency resolution failed; retrying with isolated Gradle user home " +
                "to avoid incompatible user init scripts",
        )

        val isolatedUserHome = isolatedGradleUserHomeDir()
        val retryAttempt = runCatching {
            resolveWithGradleUserHome(projectDir, isolatedUserHome.toFile())
        }

        if (retryAttempt.isSuccess) {
            return retryAttempt.getOrThrow()
        }

        logger.error(
            "Gradle dependency resolution failed (retry): ${(retryAttempt.exceptionOrNull() ?: failure).message}",
        )
        return WorkspaceResolution(emptyList(), emptyList())
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

        val modelBuilder = connection.model(IdeaProject::class.java)
            .withArguments(
                "-Dorg.gradle.daemon=true",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.configureondemand=true",
                "-Dorg.gradle.vfs.watch=true",
            )
            .setJvmArguments("-Xmx1g", "-XX:+UseG1GC")

        val ideaProject = modelBuilder.get()

        val dependencies = mutableSetOf<Path>()
        val sourceDirectories = mutableSetOf<Path>()

        ideaProject.modules.forEach { module ->
            processModule(module, dependencies, sourceDirectories)
        }

        logger.info(
            "Resolved ${dependencies.size} dependencies and ${sourceDirectories.size} source directories via Gradle Tooling API",
        )
        return WorkspaceResolution(dependencies.toList(), sourceDirectories.toList())
    }

    private fun shouldRetryWithIsolatedGradleUserHome(error: Throwable): Boolean {
        val allMessages = error.causeChain()
            .mapNotNull { it.message }
            .joinToString("\n")

        return allMessages.contains("init.d") ||
            allMessages.contains("init script") ||
            allMessages.contains("cp_init") ||
            allMessages.contains("Unsupported class file major version")
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

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

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
