package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.download.FileDownloadStartEvent
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

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

        return try {
            // Use connection pool for better performance
            val connection = GradleConnectionPool.getConnection(projectDir)
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
            WorkspaceResolution(
                dependencies = dependencies.toList(),
                sourceDirectories = sourceDirectories.toList(),
            )
        } catch (e: org.gradle.tooling.GradleConnectionException) {
            logger.error("Failed to connect to Gradle: ${e.message}", e)
            WorkspaceResolution(emptyList(), emptyList())
        } catch (e: org.gradle.tooling.BuildException) {
            logger.error("Gradle build failed during dependency resolution: ${e.message}", e)
            WorkspaceResolution(emptyList(), emptyList())
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid project directory or configuration: ${e.message}", e)
            WorkspaceResolution(emptyList(), emptyList())
        }
    }

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
