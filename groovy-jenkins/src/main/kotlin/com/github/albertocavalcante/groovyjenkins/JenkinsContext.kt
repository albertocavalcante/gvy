package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovygdsl.GdslExecutor
import com.github.albertocavalcante.groovygdsl.GdslLoadResults
import com.github.albertocavalcante.groovygdsl.GdslLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Manages the Jenkins pipeline context, including classpath and GDSL metadata.
 * Keeps Jenkins-specific compilation separate from general Groovy sources.
 */
class JenkinsContext(private val configuration: JenkinsConfiguration, private val workspaceRoot: Path) {
    private val logger = LoggerFactory.getLogger(JenkinsContext::class.java)
    private val libraryResolver = SharedLibraryResolver(configuration)
    private val gdslLoader = GdslLoader()
    private val gdslExecutor = GdslExecutor()
    private val fileDetector = JenkinsFileDetector(configuration.filePatterns)
    private val libraryParser = LibraryParser()

    /**
     * Builds the classpath for Jenkins pipeline files based on library references.
     * If no references are provided, includes all configured libraries.
     */
    fun buildClasspath(libraryReferences: List<LibraryReference>): List<Path> {
        val classpath = mutableListOf<Path>()

        // If no specific references, include all configured libraries
        val librariesToInclude = if (libraryReferences.isEmpty()) {
            configuration.sharedLibraries
        } else {
            // Resolve library references to actual jars
            val result = libraryResolver.resolveAllWithWarnings(libraryReferences)

            // Log warnings for missing libraries
            result.missing.forEach { ref ->
                logger.warn("Jenkins library '${ref.name}' referenced but not configured")
            }

            result.resolved
        }

        librariesToInclude.forEach { library ->
            // Add main jar
            val jarPath = Paths.get(library.jar)
            if (Files.exists(jarPath)) {
                classpath.add(jarPath)
                logger.debug("Added Jenkins library jar to classpath: ${library.jar}")
            } else {
                logger.warn("Jenkins library jar not found: ${library.jar}")
            }

            // Add sources jar if available
            library.sourcesJar?.let { sourcesJar ->
                val sourcesPath = Paths.get(sourcesJar)
                if (Files.exists(sourcesPath)) {
                    classpath.add(sourcesPath)
                    logger.debug("Added Jenkins library sources to classpath: $sourcesJar")
                } else {
                    logger.debug("Jenkins library sources jar not found: $sourcesJar")
                }
            }
        }

        return classpath
    }

    /**
     * Loads and executes GDSL metadata files from configured paths.
     */
    fun loadGdslMetadata(): GdslLoadResults {
        val results = gdslLoader.loadAllGdslFiles(configuration.gdslPaths)

        // Log results and execute successful loads
        results.successful.forEach { result ->
            logger.info("Loaded Jenkins GDSL: ${result.path}")
            result.content?.let { content ->
                try {
                    gdslExecutor.execute(content, result.path)
                } catch (e: Exception) {
                    logger.error("Failed to execute GDSL: ${result.path}", e)
                }
            }
        }

        results.failed.forEach { result ->
            logger.warn("Failed to load Jenkins GDSL: ${result.path} - ${result.error}")
        }

        return results
    }

    /**
     * Checks if a URI is a Jenkins pipeline file based on configured patterns.
     */
    fun isJenkinsFile(uri: java.net.URI): Boolean = fileDetector.isJenkinsFile(uri)

    /**
     * Parses library references from Jenkinsfile source.
     */
    fun parseLibraries(source: String): List<LibraryReference> = libraryParser.parseLibraries(source)
}
