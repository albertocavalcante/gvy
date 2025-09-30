package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.gradle.GradleSourceSetResolver
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Manages compilation contexts for different parts of a workspace.
 * This class is responsible for:
 * - Detecting build system structure (Gradle, Maven, etc.)
 * - Creating appropriate compilation contexts (main, test, standalone)
 * - Managing dependencies between contexts
 * - Determining which context a file belongs to
 */
@Suppress("TooGenericExceptionCaught") // Compilation context management handles all build system errors
class CompilationContextManager(
    private val gradleSourceSetResolver: GradleSourceSetResolver = GradleSourceSetResolver(),
) {
    private val logger = LoggerFactory.getLogger(CompilationContextManager::class.java)

    /**
     * Represents a compilation context with its files and dependencies.
     */
    data class CompilationContext(
        val name: String,
        val files: Set<URI>,
        val classpath: List<Path>,
        val dependencies: Set<String> = emptySet(),
        val type: ContextType = ContextType.SOURCE_SET,
    ) {
        enum class ContextType {
            SOURCE_SET, // Gradle/Maven source set (main, test)
            STANDALONE, // Standalone files not part of build system
            BUILD_SCRIPT, // Build files (*.gradle, pom.xml)
        }

        /**
         * Checks if this context contains the given file URI.
         */
        fun containsFile(uri: URI): Boolean = files.contains(uri)

        /**
         * Returns the number of files in this context.
         */
        fun fileCount(): Int = files.size
    }

    /**
     * Workspace compilation contexts indexed by name.
     */
    private var contexts: Map<String, CompilationContext> = emptyMap()
    private var workspaceRoot: Path? = null

    /**
     * Builds compilation contexts for the given workspace root.
     * This method analyzes the workspace structure and creates appropriate contexts.
     */
    suspend fun buildContexts(root: Path): Map<String, CompilationContext> {
        logger.info("Building compilation contexts for workspace: $root")
        workspaceRoot = root

        val newContexts = mutableMapOf<String, CompilationContext>()

        try {
            // Try Gradle first
            val gradleContexts = buildGradleContexts(root)
            if (gradleContexts.isNotEmpty()) {
                newContexts.putAll(gradleContexts)
                logger.info("Created ${gradleContexts.size} Gradle contexts")
            } else {
                // TODO: Try Maven
                // val mavenContexts = buildMavenContexts(root)
                // if (mavenContexts.isNotEmpty()) {
                //     newContexts.putAll(mavenContexts)
                //     logger.info("Created ${mavenContexts.size} Maven contexts")
                // }

                // TODO: Try Bazel
                // val bazelContexts = buildBazelContexts(root)
                // if (bazelContexts.isNotEmpty()) {
                //     newContexts.putAll(bazelContexts)
                //     logger.info("Created ${bazelContexts.size} Bazel contexts")
                // }

                logger.info("No build system detected, will create fallback contexts")
            }

            // Add standalone files context (files not in any source set)
            val standaloneContext = buildStandaloneContext(root, newContexts.values.toList())
            if (standaloneContext.files.isNotEmpty()) {
                newContexts[standaloneContext.name] = standaloneContext
                logger.info("Created standalone context with ${standaloneContext.fileCount()} files")
            }

            // Add build script context
            val buildScriptContext = buildBuildScriptContext(root)
            if (buildScriptContext.files.isNotEmpty()) {
                newContexts[buildScriptContext.name] = buildScriptContext
                logger.info("Created build script context with ${buildScriptContext.fileCount()} files")
            }

            // If no contexts found, create a fallback context
            if (newContexts.isEmpty()) {
                logger.info("No contexts created, building fallback context")
                newContexts["workspace"] = buildFallbackContext(root)
            }
        } catch (e: Exception) {
            logger.error("Error building compilation contexts", e)
            // Create a single fallback context with all Groovy files
            newContexts["workspace"] = buildFallbackContext(root)
        }

        contexts = newContexts

        logger.info("Built ${contexts.size} compilation contexts: ${contexts.keys}")
        contexts.forEach { (name, context) ->
            logger.debug("Context '$name': ${context.fileCount()} files, classpath: ${context.classpath.size} entries")
        }

        return contexts
    }

    /**
     * Gets the compilation context for a specific file.
     */
    fun getContextForFile(uri: URI): String? = contexts.entries.find { (_, context) ->
        context.containsFile(uri)
    }?.key

    /**
     * Gets all compilation contexts.
     */
    fun getContexts(): Map<String, CompilationContext> = contexts

    /**
     * Gets a specific compilation context by name.
     */
    fun getContext(name: String): CompilationContext? = contexts[name]

    /**
     * Gets the dependencies for a given context.
     * Returns contexts this context depends on, in dependency order.
     */
    fun getDependenciesForContext(contextName: String): List<CompilationContext> {
        val context = contexts[contextName] ?: return emptyList()

        return context.dependencies.mapNotNull { depName ->
            contexts[depName]
        }
    }

    /**
     * Gets all files in the workspace, grouped by context.
     */
    fun getAllFiles(): Map<String, Set<URI>> = contexts.mapValues { it.value.files }

    /**
     * Checks if a file should be compiled standalone (not as part of workspace).
     */
    fun shouldCompileStandalone(uri: URI): Boolean {
        val context = getContextForFile(uri)
        return context == "standalone"
    }

    /**
     * Builds Gradle-based compilation contexts.
     */
    private fun buildGradleContexts(root: Path): Map<String, CompilationContext> {
        val sourceSets = gradleSourceSetResolver.resolveSourceSets(root)
        if (sourceSets.isEmpty()) {
            return emptyMap()
        }

        val contexts = mutableMapOf<String, CompilationContext>()

        sourceSets.forEach { sourceSet ->
            val files = sourceSet.findGroovyFiles().map { it.toFile().toURI() }.toSet()

            if (files.isNotEmpty()) {
                contexts[sourceSet.name] = CompilationContext(
                    name = sourceSet.name,
                    files = files,
                    classpath = sourceSet.compileClasspath,
                    dependencies = sourceSet.dependencies,
                    type = CompilationContext.ContextType.SOURCE_SET,
                )
            }
        }

        return contexts
    }

    /**
     * Builds a context for standalone Groovy files (not part of any source set).
     */
    private fun buildStandaloneContext(root: Path, existingContexts: List<CompilationContext>): CompilationContext {
        val existingFiles = existingContexts.flatMap { it.files }.toSet()

        val standaloneFiles = root.toFile().walkTopDown()
            .maxDepth(2) // Only root and immediate subdirectories
            .filter { file ->
                file.isFile &&
                    file.extension == "groovy" &&
                    file.toPath().toFile().toURI() !in existingFiles &&
                    !file.path.contains("build/") &&
                    !file.path.contains(".gradle/") &&
                    !file.path.contains("src/") // Exclude source sets
            }
            .map { it.toPath().toFile().toURI() }
            .toSet()

        return CompilationContext(
            name = "standalone",
            files = standaloneFiles,
            classpath = emptyList(), // Standalone files get minimal classpath
            dependencies = emptySet(),
            type = CompilationContext.ContextType.STANDALONE,
        )
    }

    /**
     * Builds a context for build script files (*.gradle, *.gradle.kts).
     */
    private fun buildBuildScriptContext(root: Path): CompilationContext {
        val buildFiles = listOf("build.gradle", "settings.gradle")
            .mapNotNull { fileName ->
                val file = root.resolve(fileName)
                if (file.exists()) file.toFile().toURI() else null
            }
            .toSet()

        return CompilationContext(
            name = "build-scripts",
            files = buildFiles,
            classpath = emptyList(), // Build scripts have their own classpath
            dependencies = emptySet(),
            type = CompilationContext.ContextType.BUILD_SCRIPT,
        )
    }

    /**
     * Creates a fallback context when build system detection fails.
     */
    private fun buildFallbackContext(root: Path): CompilationContext {
        val allGroovyFiles = root.toFile().walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension == "groovy" &&
                    !file.path.contains("build/") &&
                    !file.path.contains(".gradle/")
            }
            .map { it.toPath().toFile().toURI() }
            .toSet()

        // Discover source directories for fallback classpath
        val sourceDirectories = allGroovyFiles
            .map { java.nio.file.Paths.get(it) }
            .mapNotNull { it.parent }
            .distinct()
            .toList()

        return CompilationContext(
            name = "workspace",
            files = allGroovyFiles,
            classpath = sourceDirectories, // Include discovered source directories in classpath
            dependencies = emptySet(),
            type = CompilationContext.ContextType.SOURCE_SET,
        )
    }

    /**
     * Gets workspace statistics for debugging and monitoring.
     */
    fun getWorkspaceStatistics(): Map<String, Any> {
        val totalFiles = contexts.values.sumOf { it.fileCount() }
        val contextStats = contexts.mapValues { (_, context) ->
            mapOf(
                "fileCount" to context.fileCount(),
                "classpathSize" to context.classpath.size,
                "dependencies" to context.dependencies.size,
                "type" to context.type.name,
            )
        }

        return mapOf(
            "totalContexts" to contexts.size,
            "totalFiles" to totalFiles,
            "workspaceRoot" to (workspaceRoot?.toString() ?: "not set"),
            "contexts" to contextStats,
        )
    }
}
