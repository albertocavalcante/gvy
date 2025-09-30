package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension

/**
 * Resolves Gradle source sets and their configurations using the Gradle Tooling API.
 * This class provides information about source directories, classpaths, and dependencies
 * for proper compilation context separation.
 */
@Suppress("TooGenericExceptionCaught") // Gradle API interop handles all tooling errors
class GradleSourceSetResolver {
    private val logger = LoggerFactory.getLogger(GradleSourceSetResolver::class.java)

    /**
     * Represents a Gradle source set with its directories and classpath information.
     */
    data class SourceSet(
        val name: String,
        val sourceDirs: List<Path>,
        val compileClasspath: List<Path>,
        val runtimeClasspath: List<Path>,
        val dependencies: Set<String> = emptySet(),
    ) {
        /**
         * Finds all Groovy files in this source set's source directories.
         */
        fun findGroovyFiles(): List<Path> = sourceDirs.flatMap { sourceDir ->
            if (sourceDir.exists()) {
                sourceDir.toFile().walkTopDown()
                    .filter { it.isFile && it.extension == "groovy" }
                    .map { it.toPath() }
                    .toList()
            } else {
                emptyList()
            }
        }
    }

    /**
     * Resolves all source sets from a Gradle project.
     *
     * @param projectDir The root directory of the Gradle project
     * @return List of resolved source sets with their configurations
     */
    fun resolveSourceSets(projectDir: Path): List<SourceSet> {
        if (!isGradleProject(projectDir)) {
            logger.debug("Not a Gradle project: $projectDir")
            return emptyList()
        }

        logger.info("Resolving Gradle source sets for: $projectDir")

        return try {
            val connection = GradleConnectionPool.getConnection(projectDir)

            // First, try to get the IDEA model which has better source set information
            val ideaProject = connection.model(IdeaProject::class.java).get()
            val sourceSets = extractSourceSetsFromIdeaModel(ideaProject, projectDir)

            if (sourceSets.isNotEmpty()) {
                logger.info("Resolved ${sourceSets.size} source sets using IDEA model")
                return sourceSets
            }

            // Fallback to GradleProject model if IDEA model doesn't work
            logger.debug("IDEA model didn't provide source sets, trying GradleProject model")
            val gradleProject = connection.model(GradleProject::class.java).get()
            val fallbackSourceSets = extractSourceSetsFromGradleModel(gradleProject, projectDir)

            logger.info("Resolved ${fallbackSourceSets.size} source sets using GradleProject model")
            fallbackSourceSets
        } catch (e: org.gradle.tooling.GradleConnectionException) {
            logger.error("Failed to connect to Gradle: ${e.message}", e)
            createFallbackSourceSets(projectDir)
        } catch (e: org.gradle.tooling.BuildException) {
            logger.error("Gradle build failed during source set resolution: ${e.message}", e)
            createFallbackSourceSets(projectDir)
        } catch (e: Exception) {
            logger.error("Unexpected error resolving source sets: ${e.message}", e)
            createFallbackSourceSets(projectDir)
        }
    }

    /**
     * Extracts source sets from the IDEA model, which provides better source directory information.
     */
    private fun extractSourceSetsFromIdeaModel(
        ideaProject: IdeaProject,
        @Suppress("UnusedParameter") projectDir: Path,
    ): List<SourceSet> {
        val sourceSets = mutableListOf<SourceSet>()

        ideaProject.modules.forEach { module ->
            logger.debug("Processing IDEA module: ${module.name}")

            // Extract source directories
            val mainSourceDirs = extractSourceDirectories(module.contentRoots, false)
            val testSourceDirs = extractSourceDirectories(module.contentRoots, true)

            // Extract external classpath (libraries)
            val externalClasspath = module.dependencies
                .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                .mapNotNull { dep -> dep.file?.toPath() }
                .filter { it.exists() }

            // Create main source set if it has sources
            if (mainSourceDirs.isNotEmpty()) {
                // Include source dirs + external libraries in classpath
                val mainClasspath = mainSourceDirs + externalClasspath
                sourceSets.add(
                    SourceSet(
                        name = "main",
                        sourceDirs = mainSourceDirs,
                        compileClasspath = mainClasspath,
                        runtimeClasspath = mainClasspath,
                    ),
                )
            }

            // Create test source set if it has sources
            if (testSourceDirs.isNotEmpty()) {
                // Include test sources + main sources + external libraries in classpath
                val testClasspath = testSourceDirs + mainSourceDirs + externalClasspath
                sourceSets.add(
                    SourceSet(
                        name = "test",
                        sourceDirs = testSourceDirs,
                        compileClasspath = testClasspath,
                        runtimeClasspath = testClasspath,
                        dependencies = setOf("main"),
                    ),
                )
            }
        }

        return sourceSets.distinctBy { it.name }
    }

    /**
     * Extracts source directories from IDEA content roots.
     */
    private fun extractSourceDirectories(contentRoots: DomainObjectSet<*>, isTest: Boolean): List<Path> {
        val sourceDirs = mutableListOf<Path>()
        contentRoots.forEach { root ->
            sourceDirs.addAll(extractDirectoriesFromRoot(root, isTest))
        }
        return sourceDirs
    }

    private fun extractDirectoriesFromRoot(root: Any, isTest: Boolean): List<Path> {
        try {
            val sourceDirectories = getSourceDirectoriesFromRoot(root, isTest)
            return processSourceDirectories(sourceDirectories)
        } catch (e: Exception) {
            logger.debug("Could not extract source directories from content root: ${e.message}")
            return emptyList()
        }
    }

    private fun getSourceDirectoriesFromRoot(root: Any, isTest: Boolean): Iterable<*>? = if (isTest) {
        root.javaClass.getMethod("getTestDirectories").invoke(root) as? Iterable<*>
    } else {
        root.javaClass.getMethod("getSourceDirectories").invoke(root) as? Iterable<*>
    }

    private fun processSourceDirectories(sourceDirectories: Iterable<*>?): List<Path> {
        val paths = mutableListOf<Path>()
        sourceDirectories?.forEach { dir ->
            if (dir is File) {
                val path = dir.toPath()
                if (path.exists()) {
                    paths.add(path)
                }
            }
        }
        return paths
    }

    /**
     * Fallback method using GradleProject model when IDEA model is not available.
     */
    private fun extractSourceSetsFromGradleModel(
        @Suppress("UnusedParameter") gradleProject: GradleProject,
        projectDir: Path,
    ): List<SourceSet> {
        // This is a simpler fallback - we'll look for conventional directory structure
        return createConventionalSourceSets(projectDir)
    }

    /**
     * Creates conventional source sets based on standard Gradle directory structure.
     */
    private fun createConventionalSourceSets(projectDir: Path): List<SourceSet> {
        val sourceSets = mutableListOf<SourceSet>()

        // Standard main source set
        val mainGroovyDir = projectDir.resolve("src/main/groovy")
        val mainJavaDir = projectDir.resolve("src/main/java")
        val mainSourceDirs = listOf(mainGroovyDir, mainJavaDir).filter { it.exists() }

        if (mainSourceDirs.isNotEmpty()) {
            sourceSets.add(
                SourceSet(
                    name = "main",
                    sourceDirs = mainSourceDirs,
                    compileClasspath = mainSourceDirs, // Include source dirs for symbol resolution
                    runtimeClasspath = mainSourceDirs,
                ),
            )
        }

        // Standard test source set
        val testGroovyDir = projectDir.resolve("src/test/groovy")
        val testJavaDir = projectDir.resolve("src/test/java")
        val testSourceDirs = listOf(testGroovyDir, testJavaDir).filter { it.exists() }

        if (testSourceDirs.isNotEmpty()) {
            // Test classpath should include both test sources and main sources for dependencies
            val testClasspath = testSourceDirs + mainSourceDirs
            sourceSets.add(
                SourceSet(
                    name = "test",
                    sourceDirs = testSourceDirs,
                    compileClasspath = testClasspath, // Include both test and main source dirs
                    runtimeClasspath = testClasspath,
                    dependencies = setOf("main"),
                ),
            )
        }

        return sourceSets
    }

    /**
     * Creates fallback source sets when Gradle tooling API is not available.
     */
    private fun createFallbackSourceSets(projectDir: Path): List<SourceSet> {
        logger.warn("Creating fallback source sets for: $projectDir")
        return createConventionalSourceSets(projectDir)
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

    /**
     * Finds all standalone Groovy files that are not part of any source set.
     */
    fun findStandaloneGroovyFiles(projectDir: Path, sourceSets: List<SourceSet>): List<Path> {
        val sourceSetFiles = sourceSets.flatMap { it.findGroovyFiles() }.toSet()

        return projectDir.toFile().walkTopDown()
            .maxDepth(2) // Only look in root and immediate subdirectories
            .filter { file ->
                file.isFile &&
                    file.extension == "groovy" &&
                    file.toPath() !in sourceSetFiles &&
                    !file.path.contains("build/") && // Exclude build directory
                    !file.path.contains(".gradle/") // Exclude gradle cache
            }
            .map { it.toPath() }
            .toList()
    }
}
