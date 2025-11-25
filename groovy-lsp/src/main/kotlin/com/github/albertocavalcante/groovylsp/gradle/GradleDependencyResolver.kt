package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.toList

private const val DEFAULT_MODEL_TIMEOUT_MS = 60_000L

/**
 * Gradle dependency resolver that uses the Gradle Tooling API to extract
 * binary JAR dependencies from a project.
 *
 * This is Phase 1 implementation - focuses on getting dependencies
 * on the classpath for compilation. Future phases will add source
 * JAR support and on-demand downloading.
 */
class GradleDependencyResolver : DependencyResolver {
    private val logger = LoggerFactory.getLogger(GradleDependencyResolver::class.java)
    private val modelTimeoutMs: Long = System.getProperty("groovy.lsp.gradle.timeout.ms")
        ?.toLongOrNull()
        ?: System.getenv("GROOVY_LSP_GRADLE_TIMEOUT_MS")?.toLongOrNull()
        ?: DEFAULT_MODEL_TIMEOUT_MS

    /**
     * Resolves all binary JAR dependencies from a Gradle project.
     *
     * @param projectDir The root directory of the Gradle project
     * @return List of paths to dependency JAR files
     */
    override fun resolve(projectDir: Path): WorkspaceResolution {
        if (!isGradleProject(projectDir)) {
            logger.info("Not a Gradle project: $projectDir")
            return WorkspaceResolution(emptyList(), emptyList())
        }

        logger.info("Resolving Gradle dependencies for: $projectDir")

        return try {
            val connection = GradleConnectionPool.getConnection(projectDir)
            val ideaProject = resolveIdeaProject(connection, projectDir)

            val dependencies = mutableSetOf<Path>()
            val sourceDirectories = mutableSetOf<Path>()

            ideaProject.modules.forEach { module ->
                processModule(module, dependencies, sourceDirectories)
            }

            logger.info("Resolved ${dependencies.size} dependencies and ${sourceDirectories.size} source directories")
            WorkspaceResolution(
                dependencies = dependencies.toList(),
                sourceDirectories = sourceDirectories.toList(),
            ).withFallbackIfEmpty(projectDir)
        } catch (e: TimeoutException) {
            logger.warn(
                "Gradle dependency resolution timed out after {}ms for {} - falling back to local JAR discovery",
                modelTimeoutMs,
                projectDir,
                e,
            )
            GradleConnectionPool.closeConnection(projectDir)
            fallbackLocalResolution(projectDir)
        } catch (e: org.gradle.tooling.GradleConnectionException) {
            logger.error("Failed to connect to Gradle: ${e.message}", e)
            GradleConnectionPool.closeConnection(projectDir)
            fallbackLocalResolution(projectDir)
        } catch (e: org.gradle.tooling.BuildException) {
            logger.error("Gradle build failed during dependency resolution: ${e.message}", e)
            GradleConnectionPool.closeConnection(projectDir)
            fallbackLocalResolution(projectDir)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid project directory or configuration: ${e.message}", e)
            GradleConnectionPool.closeConnection(projectDir)
            fallbackLocalResolution(projectDir)
        }
    }

    private fun resolveIdeaProject(connection: org.gradle.tooling.ProjectConnection, projectDir: Path): IdeaProject {
        val start = System.nanoTime()
        val cancellationToken = GradleConnector.newCancellationTokenSource()
        val modelBuilder = connection.model(IdeaProject::class.java).apply {
            // Keep Gradle output away from the LSP stdio channel.
            withCancellationToken(cancellationToken.token())
            setStandardOutput(OutputStream.nullOutputStream())
            setStandardError(OutputStream.nullOutputStream())
        }

        val future = CompletableFuture.supplyAsync { modelBuilder.get() }

        return try {
            future.get(modelTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            cancellationToken.cancel()
            future.cancel(true)
            throw e
        } catch (e: Exception) {
            cancellationToken.cancel()
            throw e
        } finally {
            val elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis()
            logger.info("Gradle model resolution for {} finished in {} ms", projectDir, elapsedMs)
        }
    }

    private fun WorkspaceResolution.withFallbackIfEmpty(projectDir: Path): WorkspaceResolution {
        if (dependencies.isNotEmpty()) {
            return this
        }

        logger.warn(
            "Gradle project at {} produced an empty dependency classpath; attempting local libs fallback",
            projectDir,
        )
        return fallbackLocalResolution(projectDir)
    }

    private fun fallbackLocalResolution(projectDir: Path): WorkspaceResolution {
        val libsDir = projectDir.resolve("libs")
        val jarDependencies = if (libsDir.isDirectory()) {
            Files.list(libsDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }.toList()
            }
        } else {
            emptyList()
        }

        val sources = listOf(
            projectDir.resolve("src/main/groovy"),
            projectDir.resolve("src/main/java"),
        ).filter { it.exists() }

        if (jarDependencies.isEmpty()) {
            logger.warn("Gradle dependency resolution returned no JARs and no local libs were found at {}", libsDir)
        } else {
            logger.info(
                "Using fallback dependencies from {}: {} JAR(s)",
                libsDir.toAbsolutePath(),
                jarDependencies.size,
            )
        }

        return WorkspaceResolution(
            dependencies = jarDependencies,
            sourceDirectories = sources,
        )
    }

    private fun processModule(
        module: IdeaModule,
        dependencies: MutableSet<Path>,
        sourceDirectories: MutableSet<Path>,
    ) {
        logger.debug("Processing module: ${module.name}")

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
