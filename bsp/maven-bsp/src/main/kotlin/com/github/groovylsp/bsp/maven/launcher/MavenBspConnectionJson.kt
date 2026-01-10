package com.github.groovylsp.bsp.maven.launcher

import com.google.gson.GsonBuilder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Generates BSP connection files for Maven projects.
 *
 * The connection file (.bsp/maven-bsp.json) tells IDEs how to start
 * the Maven BSP server for a project.
 */
object MavenBspConnectionJson {

    private const val SERVER_NAME = "Maven BSP"
    private const val SERVER_VERSION = "0.1.0"
    private const val BSP_VERSION = "2.1.0"
    private val SUPPORTED_LANGUAGES = listOf("java", "groovy", "kotlin")

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Generates the BSP connection JSON content.
     *
     * @param workspaceRoot The root directory of the Maven project
     * @param serverJarPath The path to the Maven BSP server JAR
     * @return JSON string for the connection file
     */
    fun generate(workspaceRoot: Path, serverJarPath: Path): String {
        val connectionData = mapOf(
            "name" to SERVER_NAME,
            "version" to SERVER_VERSION,
            "bspVersion" to BSP_VERSION,
            "languages" to SUPPORTED_LANGUAGES,
            "argv" to listOf(
                "java",
                "-jar",
                serverJarPath.toAbsolutePath().toString(),
                workspaceRoot.toAbsolutePath().toString(),
            ),
        )
        return gson.toJson(connectionData)
    }

    /**
     * Writes the BSP connection file to the workspace.
     *
     * Creates the .bsp directory if it doesn't exist and writes
     * the maven-bsp.json connection file.
     *
     * @param workspaceRoot The root directory of the Maven project
     * @param serverJarPath The path to the Maven BSP server JAR
     * @return The path to the created connection file
     */
    fun writeToWorkspace(workspaceRoot: Path, serverJarPath: Path): Path {
        val bspDir = workspaceRoot.resolve(".bsp")
        bspDir.createDirectories()

        val connectionFile = bspDir.resolve("maven-bsp.json")
        val jsonContent = generate(workspaceRoot, serverJarPath)
        connectionFile.writeText(jsonContent)

        return connectionFile
    }
}
