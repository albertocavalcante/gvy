package com.github.albertocavalcante.gvy.diagnostics.codenarc

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
        assertTrue(projectType.hasSpock)
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
        assertTrue(!projectType.hasSpock)
    }

    @Test
    fun `should fallback to plain Groovy`(@TempDir tempDir: Path) {
        // Empty directory - no project indicators

        val projectType = detector.detect(tempDir)

        assertEquals(ProjectType.PlainGroovy, projectType)
    }

    // Regression for #1200: these directory names are common in ordinary projects and must not
    // switch the workspace to the quiet Jenkins CPS-only CodeNarc profile on their own.

    @Test
    fun `generic resources directory alone is not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("resources/icons"))
        Files.createFile(tempDir.resolve("resources/icons/logo.svg"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `src com package directory alone is not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("src/com/example"))
        Files.createFile(tempDir.resolve("src/com/example/Util.groovy"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `src org package directory alone is not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("src/org/example"))
        Files.createFile(tempDir.resolve("src/org/example/Util.groovy"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `resources and src package directories together are not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("resources"))
        Files.createDirectories(tempDir.resolve("src/org/example"))
        Files.createFile(tempDir.resolve("src/org/example/Util.groovy"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `empty vars directory is not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectory(tempDir.resolve("vars"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `vars directory without Groovy scripts is not a Jenkins project`(@TempDir tempDir: Path) {
        // Ansible roles and Terraform modules use vars/ for YAML or tfvars files.
        Files.createDirectory(tempDir.resolve("vars"))
        Files.createFile(tempDir.resolve("vars/main.yml"))
        Files.createDirectories(tempDir.resolve("vars/nested"))
        Files.createFile(tempDir.resolve("vars/nested/step.groovy"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `directory named Jenkinsfile is not a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectory(tempDir.resolve("Jenkinsfile"))

        assertEquals(ProjectType.PlainGroovy, detector.detect(tempDir))
    }

    @Test
    fun `representative shared library with vars src and resources is a Jenkins project`(@TempDir tempDir: Path) {
        Files.createDirectory(tempDir.resolve("vars"))
        Files.writeString(tempDir.resolve("vars/deployApp.groovy"), "def call(Map args) { echo args.name }\n")
        Files.createDirectories(tempDir.resolve("src/org/acme/ci"))
        Files.createFile(tempDir.resolve("src/org/acme/ci/Helper.groovy"))
        Files.createDirectories(tempDir.resolve("resources/org/acme"))
        Files.createFile(tempDir.resolve("resources/org/acme/template.yaml"))

        assertEquals(ProjectType.JenkinsLibrary, detector.detect(tempDir))
    }

    @Test
    fun `Gradle project with generic resources directory stays a Gradle project`(@TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'groovy' }\n")
        Files.createDirectories(tempDir.resolve("resources"))

        assertEquals(ProjectType.GradleProject(hasSpock = false), detector.detect(tempDir))
    }
}
