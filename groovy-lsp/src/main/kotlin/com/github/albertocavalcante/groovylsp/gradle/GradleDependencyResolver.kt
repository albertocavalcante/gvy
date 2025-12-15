package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.download.FileDownloadStartEvent
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
 * Gradle dependency resolver that uses the Gradle Tooling API to extract
 * binary JAR dependencies from a project.
 *
 * This is Phase 1 implementation - focuses on getting dependencies
 * on the classpath for compilation. Future phases will add source
 * JAR support and on-demand downloading.
 */
class GradleDependencyResolver(private val connectionFactory: GradleConnectionFactory = GradleConnectionPool) :
    DependencyResolver {
    private val logger = LoggerFactory.getLogger(GradleDependencyResolver::class.java)

    /**
     * Resolves all binary JAR dependencies from a Gradle project.
     *
     * @param projectDir The root directory of the Gradle project
     * @param onDownloadProgress Optional callback for download progress updates (e.g., Gradle distribution download)
     * @return List of paths to dependency JAR files
     */
    override fun resolve(projectDir: Path, onDownloadProgress: ((String) -> Unit)?): WorkspaceResolution {
        if (!isGradleProject(projectDir)) {
            logger.info("Not a Gradle project: $projectDir")
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.info("Resolving Gradle dependencies for: $projectDir")

        val defaultAttempt = runCatching {
            resolveWithGradleUserHome(projectDir, gradleUserHomeDir = null, onDownloadProgress = onDownloadProgress)
        }

        if (defaultAttempt.isSuccess) {
            return defaultAttempt.getOrThrow()
        }

        val failure = defaultAttempt.exceptionOrNull() ?: return WorkspaceResolution(emptyList(), emptyList())
        if (!shouldRetryWithIsolatedGradleUserHome(failure)) {
            logGradleResolutionFailure(failure, "Gradle dependency resolution failed")
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.warn(
            "Gradle dependency resolution failed; retrying with isolated Gradle user home " +
                "to avoid incompatible user init scripts",
        )

        val isolatedUserHome = isolatedGradleUserHomeDir()
        val retryAttempt = runCatching {
            resolveWithGradleUserHome(projectDir, isolatedUserHome.toFile(), onDownloadProgress)
        }

        if (retryAttempt.isSuccess) {
            return retryAttempt.getOrThrow()
        }

        logGradleResolutionFailure(
            retryAttempt.exceptionOrNull() ?: failure,
            "Gradle dependency resolution failed (retry)",
        )
        return WorkspaceResolution(emptyList(), emptyList())
    }

    private fun resolveWithGradleUserHome(
        projectDir: Path,
        gradleUserHomeDir: File?,
        onDownloadProgress: ((String) -> Unit)?,
    ): WorkspaceResolution {
        val connection = connectionFactory.getConnection(projectDir, gradleUserHomeDir)
        val modelBuilder = connection.model(IdeaProject::class.java)
            .withArguments(
                "-Dorg.gradle.daemon=true",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.configureondemand=true",
                "-Dorg.gradle.vfs.watch=true",
            )
            .setJvmArguments("-Xmx1g", "-XX:+UseG1GC")

        // Add progress listener to track Gradle distribution downloads
        if (onDownloadProgress != null) {
            modelBuilder.addProgressListener({ event ->
                when (event) {
                    is FileDownloadStartEvent -> {
                        val uri = event.descriptor.uri.toString()
                        // Detect Gradle distribution download (can take 60-120s on cold CI)
                        // Matches: gradle-X.Y.Z-bin.zip, gradle-X.Y.Z-all.zip, etc.
                        // FIXME: Detection is fragile - may need more robust pattern matching
                        if (uri.contains("/gradle-") && uri.endsWith(".zip")) {
                            logger.info("Detected Gradle distribution download: $uri")
                            onDownloadProgress("Downloading Gradle distribution (this may take a few minutes)...")
                        }
                    }
                }
            }, OperationType.FILE_DOWNLOAD)
        }

        val ideaProject = modelBuilder.get()

        val dependencies = mutableSetOf<Path>()
        val sourceDirectories = mutableSetOf<Path>()

        ideaProject.modules.forEach { module ->
            processModule(module, dependencies, sourceDirectories)
        }

        logger.info("Resolved ${dependencies.size} dependencies and ${sourceDirectories.size} source directories")
        return WorkspaceResolution(
            dependencies = dependencies.toList(),
            sourceDirectories = sourceDirectories.toList(),
        )
    }

    private fun shouldRetryWithIsolatedGradleUserHome(error: Throwable): Boolean {
        val allMessages = error.causeChain()
            .mapNotNull { it.message }
            .joinToString("\n")

        // NOTE: Heuristic / tradeoff:
        // This uses message substring matching because the Tooling API doesn't expose a stable error taxonomy
        // for "broken init script" failures. We keep the patterns narrow and revisit as Gradle evolves.
        // TODO: If Gradle exposes structured failure types here, switch to deterministic detection.
        // User init scripts in ~/.gradle/init.d can break the Tooling API model fetch in unpredictable ways.
        // One common case is an init script (or plugin it loads) compiled for a newer Java version than the
        // Groovy/ASM embedded in the Gradle distribution.
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

    private fun logGradleResolutionFailure(error: Throwable, message: String) {
        logger.error("$message: ${error.message}", error)
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun processModule(
        module: IdeaModule,
        dependencies: MutableSet<Path>,
        sourceDirectories: MutableSet<Path>,
    ) {
        logger.debug("Processing module: ${module.name}")

        module.dependencies.forEach { dep ->
            logger.debug("Dependency found: $dep (type: ${dep.javaClass.name})")
        }

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

        module.contentRoots?.forEach { root ->
            root.sourceDirectories?.forEach { dir ->
                dir.directory?.toPath()?.takeIf { it.exists() }?.let(sourceDirectories::add)
            }
            root.testDirectories?.forEach { dir ->
                dir.directory?.toPath()?.takeIf { it.exists() }?.let(sourceDirectories::add)
            }
        }
    }

    /**
     * Checks if the given directory is a Gradle project.
     */
    private fun isGradleProject(projectDir: Path): Boolean {
        val gradleFiles = listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
        )

        return gradleFiles.any { fileName ->
            val candidate = projectDir.resolve(fileName)
            val present = candidate.exists()
            logger.debug("Gradle probe: {} present={}", candidate, present)
            present
        }
    }
}
