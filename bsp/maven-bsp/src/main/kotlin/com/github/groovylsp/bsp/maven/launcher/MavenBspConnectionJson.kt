package com.github.groovylsp.bsp.maven.launcher

import com.github.groovylsp.bsp.maven.server.MavenBspCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private val json = Json { prettyPrint = true }

    /**
     * BSP connection data structure.
     */
    @Serializable
    private data class BspConnectionData(
        val name: String,
        val version: String,
        val bspVersion: String,
        val languages: List<String>,
        val argv: List<String>,
    )

    /**
     * Generates the BSP connection JSON content.
     *
     * @param workspaceRoot The root directory of the Maven project
     * @param serverJarPath The path to the Maven BSP server JAR
     * @return JSON string for the connection file
     */
    fun generate(workspaceRoot: Path, serverJarPath: Path): String {
        val connectionData = BspConnectionData(
            name = SERVER_NAME,
            version = SERVER_VERSION,
            bspVersion = BSP_VERSION,
            languages = MavenBspCapabilities.supportedLanguages(),
            argv = listOf(
                "java",
                "-jar",
                serverJarPath.toAbsolutePath().toString(),
                workspaceRoot.toAbsolutePath().toString(),
            ),
        )
        return json.encodeToString(connectionData)
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
