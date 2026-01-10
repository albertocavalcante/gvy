package com.github.groovylsp.bsp.maven.launcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * TDD tests for MavenBspConnectionJson.
 *
 * These tests verify:
 * - Valid JSON output generation
 * - Correct BSP connection fields
 * - Proper argv command construction
 */
class MavenBspConnectionJsonTest {

    @TempDir
    lateinit var tempDir: Path

    private val gson = Gson()

    @Nested
    inner class JsonGeneration {

        @Test
        fun `should generate valid JSON`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)

            // Then: Should be valid JSON
            val parsed = gson.fromJson(json, JsonObject::class.java)
            assertThat(parsed).isNotNull
        }

        @Test
        fun `should include name field`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            assertThat(parsed.get("name").asString).isEqualTo("Maven BSP")
        }

        @Test
        fun `should include version field`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            assertThat(parsed.has("version")).isTrue()
            assertThat(parsed.get("version").asString).isNotBlank()
        }

        @Test
        fun `should include bspVersion 2_1_0`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            assertThat(parsed.get("bspVersion").asString).isEqualTo("2.1.0")
        }

        @Test
        fun `should include supported languages`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            val languages = parsed.getAsJsonArray("languages").map { it.asString }
            assertThat(languages).containsExactlyInAnyOrder("java", "groovy", "kotlin")
        }
    }

    @Nested
    inner class ArgvConstruction {

        @Test
        fun `should include java command`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            val argv = parsed.getAsJsonArray("argv").map { it.asString }
            assertThat(argv.first()).isEqualTo("java")
        }

        @Test
        fun `should include jar flag and path`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            val argv = parsed.getAsJsonArray("argv").map { it.asString }
            assertThat(argv).contains("-jar")
            assertThat(argv).contains(serverJarPath.toString())
        }

        @Test
        fun `should include workspace root as argument`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            val argv = parsed.getAsJsonArray("argv").map { it.asString }
            assertThat(argv.last()).isEqualTo(workspaceRoot.toString())
        }

        @Test
        fun `should use absolute paths`() {
            // Given
            val workspaceRoot = tempDir.resolve("project")
            val serverJarPath = tempDir.resolve("lib/maven-bsp.jar")

            // When
            val json = MavenBspConnectionJson.generate(workspaceRoot, serverJarPath)
            val parsed = gson.fromJson(json, JsonObject::class.java)

            // Then
            val argv = parsed.getAsJsonArray("argv").map { it.asString }
            assertThat(argv).anyMatch { it.startsWith("/") || it.matches(Regex("[A-Z]:.*")) }
        }
    }

    @Nested
    inner class FileGeneration {

        @Test
        fun `should write connection file to bsp directory`() {
            // Given
            val workspaceRoot = tempDir
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val bspFile = MavenBspConnectionJson.writeToWorkspace(workspaceRoot, serverJarPath)

            // Then
            assertThat(bspFile).exists()
            assertThat(bspFile.parent.fileName.toString()).isEqualTo(".bsp")
            assertThat(bspFile.fileName.toString()).isEqualTo("maven-bsp.json")
        }

        @Test
        fun `should create bsp directory if not exists`() {
            // Given
            val workspaceRoot = tempDir.resolve("newproject")
            workspaceRoot.toFile().mkdirs()
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            val bspFile = MavenBspConnectionJson.writeToWorkspace(workspaceRoot, serverJarPath)

            // Then
            assertThat(bspFile.parent).exists()
            assertThat(bspFile.parent).isDirectory()
        }

        @Test
        fun `should overwrite existing connection file`() {
            // Given
            val workspaceRoot = tempDir
            val bspDir = workspaceRoot.resolve(".bsp")
            bspDir.toFile().mkdirs()
            val existingFile = bspDir.resolve("maven-bsp.json")
            existingFile.toFile().writeText("""{"previousKey": "previousValue"}""")
            val serverJarPath = tempDir.resolve("maven-bsp.jar")

            // When
            MavenBspConnectionJson.writeToWorkspace(workspaceRoot, serverJarPath)

            // Then
            val content = existingFile.toFile().readText()
            assertThat(content).contains("Maven BSP")
            assertThat(content).doesNotContain("previousKey")
        }
    }
}
