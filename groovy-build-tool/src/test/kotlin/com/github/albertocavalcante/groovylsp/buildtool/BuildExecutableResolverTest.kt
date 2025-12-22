package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile

class BuildExecutableResolverTest {

    @TempDir
    lateinit var tempDir: Path

    // ==================== Gradle Tests ====================

    @Test
    fun `resolveGradle returns wrapper path when gradlew exists on Unix`() {
        // Setup: create gradlew wrapper
        tempDir.resolve("gradlew").createFile()

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = false)

        assertEquals("./gradlew", result)
    }

    @Test
    fun `resolveGradle returns wrapper name when gradlew_bat exists on Windows`() {
        // Setup: create gradlew.bat wrapper
        tempDir.resolve("gradlew.bat").createFile()

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = true)

        assertEquals("gradlew.bat", result)
    }

    @Test
    fun `resolveGradle returns system gradle when no wrapper exists on Unix`() {
        // No wrapper created

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = false)

        assertEquals("gradle", result)
    }

    @Test
    fun `resolveGradle returns system gradle when no wrapper exists on Windows`() {
        // No wrapper created

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = true)

        assertEquals("gradle", result)
    }

    // ==================== Maven Tests ====================

    @Test
    fun `resolveMaven returns wrapper path when mvnw exists on Unix`() {
        // Setup: create mvnw wrapper
        tempDir.resolve("mvnw").createFile()

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = false)

        assertEquals("./mvnw", result)
    }

    @Test
    fun `resolveMaven returns wrapper name when mvnw_cmd exists on Windows`() {
        // Setup: create mvnw.cmd wrapper
        tempDir.resolve("mvnw.cmd").createFile()

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = true)

        assertEquals("mvnw.cmd", result)
    }

    @Test
    fun `resolveMaven returns system mvn when no wrapper exists on Unix`() {
        // No wrapper created

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = false)

        assertEquals("mvn", result)
    }

    @Test
    fun `resolveMaven returns system mvn when no wrapper exists on Windows`() {
        // No wrapper created

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = true)

        assertEquals("mvn", result)
    }
}
