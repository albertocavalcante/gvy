package com.github.albertocavalcante.reports.discovery

import com.github.albertocavalcante.reports.xml.XmlUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Utilities for discovering project structure (Maven modules, Gradle subprojects).
 *
 * Extracted from duplicated code in JaCoCo and Surefire parsers.
 */
object ProjectDiscovery {

    /**
     * Discover Maven submodules from pom.xml.
     *
     * @param workspaceRoot The root directory of the workspace
     * @return List of module names defined in pom.xml, or empty list if none found
     */
    fun discoverMavenModules(workspaceRoot: File): List<String> {
        val pomFile = File(workspaceRoot, "pom.xml")
        if (!pomFile.exists()) return emptyList()

        return try {
            val dBuilder = XmlUtils.createSecureDocumentBuilder()
            val doc = dBuilder.parse(pomFile)
            doc.documentElement.normalize()

            val moduleNodes = doc.getElementsByTagName("module")
            (0 until moduleNodes.length).mapNotNull { i ->
                moduleNodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (
            // Catch all XML parsing errors and return empty list
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            logger.warn(e) { "Failed to parse pom.xml for modules" }
            emptyList()
        }
    }

    /**
     * Find Gradle subprojects by looking for build.gradle or build.gradle.kts files.
     *
     * @param workspaceRoot The root directory of the workspace
     * @return List of directories containing Gradle build files
     */
    fun findGradleSubprojects(workspaceRoot: File): List<File> = workspaceRoot.listFiles()
        ?.filter { it.isDirectory && it.name !in setOf("build", ".gradle") }
        ?.filter { File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists() }
        ?: emptyList()
}
