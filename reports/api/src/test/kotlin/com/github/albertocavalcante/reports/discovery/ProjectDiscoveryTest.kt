package com.github.albertocavalcante.reports.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectDiscoveryTest {

    @Test
    fun `discoverMavenModules returns empty list when pom xml does not exist`(@TempDir tempDir: File) {
        val modules = ProjectDiscovery.discoverMavenModules(tempDir)
        assertThat(modules).isEmpty()
    }

    @Test
    fun `discoverMavenModules returns module names from pom xml`(@TempDir tempDir: File) {
        val pomFile = File(tempDir, "pom.xml")
        pomFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                    <module>module-c</module>
                </modules>
            </project>
            """.trimIndent(),
        )

        val modules = ProjectDiscovery.discoverMavenModules(tempDir)
        assertThat(modules).containsExactly("module-a", "module-b", "module-c")
    }

    @Test
    fun `discoverMavenModules handles empty module elements`(@TempDir tempDir: File) {
        val pomFile = File(tempDir, "pom.xml")
        pomFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modules>
                    <module>module-a</module>
                    <module></module>
                    <module>  </module>
                    <module>module-b</module>
                </modules>
            </project>
            """.trimIndent(),
        )

        val modules = ProjectDiscovery.discoverMavenModules(tempDir)
        assertThat(modules).containsExactly("module-a", "module-b")
    }

    @Test
    fun `discoverMavenModules returns empty list when pom xml is malformed`(@TempDir tempDir: File) {
        val pomFile = File(tempDir, "pom.xml")
        pomFile.writeText("not valid xml")

        val modules = ProjectDiscovery.discoverMavenModules(tempDir)
        assertThat(modules).isEmpty()
    }

    @Test
    fun `findGradleSubprojects returns empty list when no subprojects exist`(@TempDir tempDir: File) {
        val subprojects = ProjectDiscovery.findGradleSubprojects(tempDir)
        assertThat(subprojects).isEmpty()
    }

    @Test
    fun `findGradleSubprojects finds directories with build gradle`(@TempDir tempDir: File) {
        val subproject1 = File(tempDir, "subproject1")
        subproject1.mkdir()
        File(subproject1, "build.gradle").createNewFile()

        val subproject2 = File(tempDir, "subproject2")
        subproject2.mkdir()
        File(subproject2, "build.gradle.kts").createNewFile()

        val notASubproject = File(tempDir, "regular-dir")
        notASubproject.mkdir()

        val subprojects = ProjectDiscovery.findGradleSubprojects(tempDir)
        assertThat(subprojects).containsExactlyInAnyOrder(subproject1, subproject2)
    }

    @Test
    fun `findGradleSubprojects excludes build and gradle directories`(@TempDir tempDir: File) {
        val buildDir = File(tempDir, "build")
        buildDir.mkdir()
        File(buildDir, "build.gradle").createNewFile()

        val gradleDir = File(tempDir, ".gradle")
        gradleDir.mkdir()
        File(gradleDir, "build.gradle").createNewFile()

        val validSubproject = File(tempDir, "subproject")
        validSubproject.mkdir()
        File(validSubproject, "build.gradle").createNewFile()

        val subprojects = ProjectDiscovery.findGradleSubprojects(tempDir)
        assertThat(subprojects).containsExactly(validSubproject)
    }

    @Test
    fun `findGradleSubprojects handles empty workspace`(@TempDir tempDir: File) {
        // Empty temp directory
        val subprojects = ProjectDiscovery.findGradleSubprojects(tempDir)
        assertThat(subprojects).isEmpty()
    }
}
