package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Simple dependency resolver that uses Gradle Tooling API to extract
 * binary JAR dependencies from a Gradle project.
 *
 * This is Phase 1 implementation - focuses on getting dependencies
 * on the classpath for compilation. Future phases will add source
 * JAR support and on-demand downloading.
 */
class SimpleDependencyResolver {
    private val logger = LoggerFactory.getLogger(SimpleDependencyResolver::class.java)

    /**
     * Resolves all binary JAR dependencies from a Gradle project.
     *
     * @param projectDir The root directory of the Gradle project
     * @return List of paths to dependency JAR files
     */
    fun resolveDependencies(projectDir: Path): List<Path> {
        if (!isGradleProject(projectDir)) {
            logger.info("Not a Gradle project: $projectDir")
            return emptyList()
        }

        logger.info("Resolving Gradle dependencies for: $projectDir")

        return try {
            // Use connection pool for better performance
            val connection = GradleConnectionPool.getConnection(projectDir)
            val ideaProject = connection.model(IdeaProject::class.java).get()

            val dependencies = ideaProject.modules.flatMap { module ->
                logger.debug("Processing module: ${module.name}")

                module.dependencies
                    .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                    .mapNotNull { dependency ->
                        val jarPath = dependency.file.toPath()

                        if (jarPath.exists()) {
                            logger.debug("Found dependency: ${dependency.file.name}")
                            jarPath
                        } else {
                            logger.warn("Dependency JAR not found: $jarPath")
                            null
                        }
                    }
            }.distinct()

            logger.info("Resolved ${dependencies.size} dependencies")
            dependencies
        } catch (e: org.gradle.tooling.GradleConnectionException) {
            logger.error("Failed to connect to Gradle: ${e.message}", e)
            emptyList()
        } catch (e: org.gradle.tooling.BuildException) {
            logger.error("Gradle build failed during dependency resolution: ${e.message}", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid project directory or configuration: ${e.message}", e)
            emptyList()
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
            projectDir.resolve(fileName).exists()
        }
    }
}
