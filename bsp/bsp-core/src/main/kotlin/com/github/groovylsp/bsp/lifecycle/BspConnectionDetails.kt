package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 *
 * Based on the Metals pattern for BSP server discovery and connection management.
 */
@Serializable
data class BspConnectionDetails(
    val name: String,
    val version: String,
    val bspVersion: String,
    val languages: List<String>,
    val argv: List<String>,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val jsonParser = Json { ignoreUnknownKeys = true }

        /**
         * Parse a BSP connection details file.
         *
         * @param file Path to the JSON file
         * @return Either a ParseError or the parsed BspConnectionDetails
         */
        fun parse(file: Path): Either<ParseError, BspConnectionDetails> {
            if (!file.exists()) {
                return ParseError.FileNotFound(file).left()
            }
            @Suppress("TooGenericExceptionCaught") // File I/O failures: IOException, SecurityException, etc.

            val jsonContent = try {
                file.readText()
            } catch (e: Exception) {
                logger.warn { "Failed to read BSP connection file ${file.name}: ${e.message}" }
                return ParseError.ReadError(file, e.message ?: "Unknown error").left()
            }
            @Suppress("TooGenericExceptionCaught") // JSON parsing: SerializationException, etc.

            return try {
                val conn = jsonParser.decodeFromString<BspConnectionFile>(jsonContent)

                if (conn.argv.isEmpty()) {
                    logger.warn { "BSP connection file ${file.name} has empty argv" }
                    return ParseError.InvalidFormat(file, "argv field is empty").left()
                }

                BspConnectionDetails(
                    name = conn.name,
                    version = conn.version,
                    bspVersion = conn.bspVersion,
                    languages = conn.languages,
                    argv = conn.argv,
                ).right()
            } catch (e: Exception) {
                logger.warn { "Failed to parse BSP connection file ${file.name}: ${e.message}" }
                ParseError.JsonParseError(file, e.message ?: "Unknown error").left()
            }
        }

        /**
         * Find all BSP connection details files in the workspace.
         *
         * @param workspace Path to the workspace root
         * @return List of all valid BspConnectionDetails found
         */
        fun findAll(workspace: Path): List<BspConnectionDetails> {
            val files = findConnectionFiles(workspace)
            return files.mapNotNull { file ->
                parse(file).fold(
                    ifLeft = { error ->
                        logger.debug { "Skipping ${file.name}: $error" }
                        null
                    },
                    ifRight = { details ->
                        logger.info { "Found BSP server '${details.name}' v${details.version} from ${file.name}" }
                        details
                    },
                )
            }
        }

        /**
         * Find the first valid BSP connection details in the workspace.
         *
         * @param workspace Path to the workspace root
         * @return The first valid BspConnectionDetails, or null if none found
         */
        fun findFirst(workspace: Path): BspConnectionDetails? {
            val files = findConnectionFiles(workspace)
            return files.firstNotNullOfOrNull { file ->
                parse(file).fold(
                    ifLeft = { null },
                    ifRight = { details ->
                        logger.info { "Found BSP server '${details.name}' v${details.version} from ${file.name}" }
                        details
                    },
                )
            }
        }

        /**
         * Find all BSP connection files in the .bsp directory.
         *
         * @param workspace Path to the workspace root
         * @return List of paths to BSP connection JSON files
         */
        private fun findConnectionFiles(workspace: Path): List<Path> {
            val bspDir = workspace.resolve(".bsp")
            if (!bspDir.exists()) {
                logger.debug { "No .bsp directory found in workspace: $workspace" }
                return emptyList()
            }

            return try {
                Files.list(bspDir).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension == "json" }
                        .toList()
                }
            } catch (e: Exception) {
                logger.warn { "Failed to list .bsp directory: ${e.message}" }
                emptyList()
            }
        }
    }

    /**
     * Errors that can occur when parsing BSP connection details.
     */
    sealed class ParseError(val message: String) {
        data class FileNotFound(val file: Path) : ParseError("File not found: ${file.name}")

        data class ReadError(val file: Path, val reason: String) :
            ParseError("Failed to read ${file.name}: $reason")

        data class JsonParseError(val file: Path, val reason: String) :
            ParseError("Failed to parse ${file.name}: $reason")

        data class InvalidFormat(val file: Path, val reason: String) :
            ParseError("Invalid format in ${file.name}: $reason")

        override fun toString(): String = message
    }

    /**
     * Internal serialization model matching the BSP JSON format.
     */
    @Serializable
    private data class BspConnectionFile(
        val name: String,
        val version: String,
        @SerialName("bspVersion") val bspVersion: String,
        val languages: List<String> = emptyList(),
        val argv: List<String>,
    )
}
