package com.github.groovylsp.bsp.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BspConnectionDetailsTest {

    @Test
    fun `parse reads valid BSP connection file`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("gradle.json")
        jsonFile.writeText(
            """
            {
              "name": "Gradle BSP",
              "version": "1.0.0",
              "bspVersion": "2.1.0",
              "languages": ["java", "kotlin", "groovy"],
              "argv": ["java", "-jar", "/path/to/gradle-bsp.jar"]
            }
            """.trimIndent(),
        )

        val result = BspConnectionDetails.parse(jsonFile)

        assertThat(result.isRight()).isTrue()
        result.onRight { details ->
            assertThat(details.name).isEqualTo("Gradle BSP")
            assertThat(details.version).isEqualTo("1.0.0")
            assertThat(details.bspVersion).isEqualTo("2.1.0")
            assertThat(details.languages).containsExactly("java", "kotlin", "groovy")
            assertThat(details.argv).containsExactly("java", "-jar", "/path/to/gradle-bsp.jar")
        }
    }

    @Test
    fun `parse handles missing languages field`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("bsp.json")
        jsonFile.writeText(
            """
            {
              "name": "Test BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["java", "-jar", "bsp.jar"]
            }
            """.trimIndent(),
        )

        val result = BspConnectionDetails.parse(jsonFile)

        assertThat(result.isRight()).isTrue()
        result.onRight { details ->
            assertThat(details.languages).isEmpty()
        }
    }

    @Test
    fun `parse returns FileNotFound for non-existent file`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("nonexistent.json")

        val result = BspConnectionDetails.parse(jsonFile)

        assertThat(result.isLeft()).isTrue()
        result.onLeft { error ->
            assertThat(error).isInstanceOf(BspConnectionDetails.ParseError.FileNotFound::class.java)
            assertThat(error.message).contains("nonexistent.json")
        }
    }

    @Test
    fun `parse returns JsonParseError for malformed JSON`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("malformed.json")
        jsonFile.writeText("{ this is not valid JSON }")

        val result = BspConnectionDetails.parse(jsonFile)

        assertThat(result.isLeft()).isTrue()
        result.onLeft { error ->
            assertThat(error).isInstanceOf(BspConnectionDetails.ParseError.JsonParseError::class.java)
        }
    }

    @Test
    fun `parse returns InvalidFormat for empty argv`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("empty-argv.json")
        jsonFile.writeText(
            """
            {
              "name": "Test BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": []
            }
            """.trimIndent(),
        )

        val result = BspConnectionDetails.parse(jsonFile)

        assertThat(result.isLeft()).isTrue()
        result.onLeft { error ->
            assertThat(error).isInstanceOf(BspConnectionDetails.ParseError.InvalidFormat::class.java)
            assertThat(error.message).contains("argv field is empty")
        }
    }

    @Test
    fun `parse ignores unknown JSON fields`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("extra-fields.json")
        jsonFile.writeText(
            """
            {
              "name": "Test BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["java", "-jar", "bsp.jar"],
              "unknownField": "should be ignored",
              "anotherUnknown": 123
            }
            """.trimIndent(),
        )

        val result = BspConnectionDetails.parse(jsonFile)

        // Should succeed despite unknown fields
        assertThat(result.isRight()).isTrue()
    }

    @Test
    fun `findAll returns all valid connection files`(@TempDir tempDir: Path) {
        val bspDir = tempDir.resolve(".bsp")
        bspDir.createDirectories()

        // Valid connection file 1
        bspDir.resolve("gradle.json").writeText(
            """
            {
              "name": "Gradle BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["gradle"]
            }
            """.trimIndent(),
        )

        // Valid connection file 2
        bspDir.resolve("maven.json").writeText(
            """
            {
              "name": "Maven BSP",
              "version": "2.0",
              "bspVersion": "2.0",
              "argv": ["mvn"]
            }
            """.trimIndent(),
        )

        // Invalid connection file (should be skipped)
        bspDir.resolve("invalid.json").writeText("{ invalid json }")

        val connections = BspConnectionDetails.findAll(tempDir)

        assertThat(connections).hasSize(2)
        assertThat(connections.map { it.name }).containsExactlyInAnyOrder("Gradle BSP", "Maven BSP")
    }

    @Test
    fun `findAll returns empty list when no bsp directory exists`(@TempDir tempDir: Path) {
        val connections = BspConnectionDetails.findAll(tempDir)

        assertThat(connections).isEmpty()
    }

    @Test
    fun `findAll ignores non-json files`(@TempDir tempDir: Path) {
        val bspDir = tempDir.resolve(".bsp")
        bspDir.createDirectories()

        bspDir.resolve("gradle.json").writeText(
            """
            {
              "name": "Gradle BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["gradle"]
            }
            """.trimIndent(),
        )

        // Non-JSON files should be ignored
        bspDir.resolve("readme.txt").writeText("This is not a BSP file")
        bspDir.resolve("config.xml").writeText("<config/>")

        val connections = BspConnectionDetails.findAll(tempDir)

        assertThat(connections).hasSize(1)
        assertThat(connections[0].name).isEqualTo("Gradle BSP")
    }

    @Test
    fun `findFirst returns first valid connection`(@TempDir tempDir: Path) {
        val bspDir = tempDir.resolve(".bsp")
        bspDir.createDirectories()

        bspDir.resolve("gradle.json").writeText(
            """
            {
              "name": "Gradle BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["gradle"]
            }
            """.trimIndent(),
        )

        bspDir.resolve("maven.json").writeText(
            """
            {
              "name": "Maven BSP",
              "version": "2.0",
              "bspVersion": "2.0",
              "argv": ["mvn"]
            }
            """.trimIndent(),
        )

        val connection = BspConnectionDetails.findFirst(tempDir)

        assertThat(connection).isNotNull
        // Should return one of the two (order may vary based on filesystem)
        assertThat(connection!!.name).isIn("Gradle BSP", "Maven BSP")
    }

    @Test
    fun `findFirst returns null when no valid connections exist`(@TempDir tempDir: Path) {
        val connection = BspConnectionDetails.findFirst(tempDir)

        assertThat(connection).isNull()
    }

    @Test
    fun `findFirst skips invalid files and returns first valid one`(@TempDir tempDir: Path) {
        val bspDir = tempDir.resolve(".bsp")
        bspDir.createDirectories()

        // Invalid files
        bspDir.resolve("invalid1.json").writeText("{ bad json }")
        bspDir.resolve("invalid2.json").writeText(
            """
            {
              "name": "Missing argv",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": []
            }
            """.trimIndent(),
        )

        // Valid file
        bspDir.resolve("valid.json").writeText(
            """
            {
              "name": "Valid BSP",
              "version": "1.0",
              "bspVersion": "2.0",
              "argv": ["bsp"]
            }
            """.trimIndent(),
        )

        val connection = BspConnectionDetails.findFirst(tempDir)

        assertThat(connection).isNotNull
        assertThat(connection!!.name).isEqualTo("Valid BSP")
    }

    @Test
    fun `ParseError types have descriptive messages`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("test.json")

        val fileNotFound = BspConnectionDetails.ParseError.FileNotFound(file)
        assertThat(fileNotFound.message).contains("File not found")
        assertThat(fileNotFound.message).contains("test.json")

        val readError = BspConnectionDetails.ParseError.ReadError(file, "Permission denied")
        assertThat(readError.message).contains("Failed to read")
        assertThat(readError.message).contains("Permission denied")

        val jsonError = BspConnectionDetails.ParseError.JsonParseError(file, "Invalid syntax")
        assertThat(jsonError.message).contains("Failed to parse")
        assertThat(jsonError.message).contains("Invalid syntax")

        val formatError = BspConnectionDetails.ParseError.InvalidFormat(file, "Missing field")
        assertThat(formatError.message).contains("Invalid format")
        assertThat(formatError.message).contains("Missing field")
    }
}
