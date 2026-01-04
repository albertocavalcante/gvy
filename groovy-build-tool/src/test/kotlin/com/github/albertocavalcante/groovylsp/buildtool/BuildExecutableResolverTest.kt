package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createFile

class BuildExecutableResolverTest {

    @TempDir
    lateinit var tempDir: Path

    // ==================== Gradle Tests ====================

    @Test
    fun `resolveGradle returns wrapper path when gradlew exists on Unix`() {
        // Setup: create gradlew wrapper with executable permission
        val wrapper = tempDir.resolve("gradlew").createFile()
        makeExecutable(wrapper)

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = false)

        assertEquals(wrapper.toAbsolutePath().toString(), result)
    }

    @Test
    fun `resolveGradle returns wrapper path when gradlew_bat exists on Windows`() {
        // Setup: create gradlew.bat wrapper (no executable check on Windows)
        val wrapper = tempDir.resolve("gradlew.bat").createFile()

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = true)

        assertEquals(wrapper.toAbsolutePath().toString(), result)
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

    @Test
    fun `resolveGradle falls back to system gradle when wrapper not executable on Unix`() {
        // Setup: create gradlew wrapper WITHOUT executable permission
        tempDir.resolve("gradlew").createFile()

        val result = BuildExecutableResolver.resolveGradle(tempDir, forceWindows = false)

        assertEquals("gradle", result)
    }

    // ==================== Maven Tests ====================

    @Test
    fun `resolveMaven returns wrapper path when mvnw exists on Unix`() {
        // Setup: create mvnw wrapper with executable permission
        val wrapper = tempDir.resolve("mvnw").createFile()
        makeExecutable(wrapper)

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = false)

        assertEquals(wrapper.toAbsolutePath().toString(), result)
    }

    @Test
    fun `resolveMaven returns wrapper path when mvnw_cmd exists on Windows`() {
        // Setup: create mvnw.cmd wrapper (no executable check on Windows)
        val wrapper = tempDir.resolve("mvnw.cmd").createFile()

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = true)

        assertEquals(wrapper.toAbsolutePath().toString(), result)
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

    @Test
    fun `resolveMaven falls back to system mvn when wrapper not executable on Unix`() {
        // Setup: create mvnw wrapper WITHOUT executable permission
        tempDir.resolve("mvnw").createFile()

        val result = BuildExecutableResolver.resolveMaven(tempDir, forceWindows = false)

        assertEquals("mvn", result)
    }

    private fun makeExecutable(path: Path) {
        val fileStore = Files.getFileStore(path)
        val supportsPosix = fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java)
        if (!supportsPosix) return

        val perms = Files.getPosixFilePermissions(path).toMutableSet()
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        Files.setPosixFilePermissions(path, perms)
    }
}
