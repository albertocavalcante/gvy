package com.github.albertocavalcante.groovylsp.buildtool.bsp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * BSP connection details parsed from .bsp directory JSON files.
 * BSP servers advertise themselves via JSON files in the .bsp directory.
 */
data class BspConnectionDetails(
    val name: String,
    val version: String,
    val bspVersion: String,
    val languages: List<String>,
    val argv: List<String>,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BspConnectionDetails::class.java)
        private val jsonParser = Json { ignoreUnknownKeys = true }

        fun findConnectionFiles(workspaceRoot: Path): List<Path> {
            val bspDir = workspaceRoot.resolve(".bsp")
            if (!bspDir.exists()) return emptyList()

            return runCatching {
                Files.list(bspDir).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension == "json" }.toList()
                }
            }.onFailure {
                logger.warn("Failed to list .bsp directory: ${it.message}")
            }.getOrDefault(emptyList())
        }

        fun findFirst(workspaceRoot: Path): BspConnectionDetails? =
            findConnectionFiles(workspaceRoot).firstNotNullOfOrNull { file ->
                parse(file)?.also {
                    logger.info("Found BSP server '${it.name}' v${it.version} from ${file.name}")
                }
            }

        fun parse(file: Path): BspConnectionDetails? {
            if (!file.exists()) return null
            return runCatching {
                parseJson(file.readText())
            }.onFailure {
                logger.warn("Failed to parse BSP connection file ${file.name}: ${it.message}")
            }.getOrNull()
        }

        internal fun parseJson(json: String): BspConnectionDetails? {
            val conn = runCatching {
                jsonParser.decodeFromString<BspConnectionFile>(json)
            }.getOrElse { return null }

            if (conn.argv.isEmpty()) {
                logger.warn("BSP connection file has empty argv")
                return null
            }

            return BspConnectionDetails(conn.name, conn.version, conn.bspVersion, conn.languages, conn.argv)
        }
    }

    @Serializable
    private data class BspConnectionFile(
        val name: String,
        val version: String,
        @SerialName("bspVersion") val bspVersion: String,
        val languages: List<String> = emptyList(),
        val argv: List<String>,
    )
}
