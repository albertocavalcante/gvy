package com.github.albertocavalcante.diagnostics.codenarc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test suite for ProjectTypeDetector focused on Jenkins and Spock use cases.
 */
class ProjectTypeDetectorTest {

    private val detector = DefaultProjectTypeDetector()

    @Test
    fun `should detect Jenkins shared library project`(@TempDir tempDir: Path) {
        // Create Jenkins shared library structure
        Files.createDirectory(tempDir.resolve("vars"))
        Files.createFile(tempDir.resolve("vars/myPipeline.groovy"))

        val projectType = detector.detect(tempDir)

        assertEquals(ProjectType.JenkinsLibrary, projectType)
    }

    @Test
    fun `should detect Jenkins project by Jenkinsfile`(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("Jenkinsfile"))

        val projectType = detector.detect(tempDir)

        assertEquals(ProjectType.JenkinsLibrary, projectType)
    }

    @Test
    fun `should detect Gradle project with Spock`(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("build.gradle"))
        Files.writeString(
            tempDir.resolve("build.gradle"),
            """
            dependencies {
                testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
            }
            """.trimIndent(),
        )

        val projectType = detector.detect(tempDir)

        assertTrue(projectType is ProjectType.GradleProject)
        assertTrue((projectType as ProjectType.GradleProject).hasSpock)
    }

    @Test
    fun `should detect plain Gradle project without Spock`(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("build.gradle"))
        Files.writeString(
            tempDir.resolve("build.gradle"),
            """
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
            }
            """.trimIndent(),
        )

        val projectType = detector.detect(tempDir)

        assertTrue(projectType is ProjectType.GradleProject)
        assertTrue(!(projectType as ProjectType.GradleProject).hasSpock)
    }

    @Test
    fun `should fallback to plain Groovy`(@TempDir tempDir: Path) {
        // Empty directory - no project indicators

        val projectType = detector.detect(tempDir)

        assertEquals(ProjectType.PlainGroovy, projectType)
    }
}
