package com.github.albertocavalcante.groovylsp.buildtool

import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GroovyVersionDetectorTest {

    private val detector = GroovyVersionDetector()

    @Test
    fun `should detect Groovy 4 from Maven dependency`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4.0.15"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0.15", result?.version)
        assertEquals("4.0", result?.majorMinor)
        assertEquals("pom.xml", result?.source)
    }

    @Test
    fun `should detect Groovy 3 from Maven dependency`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.codehaus.groovy"
        dep.artifactId = "groovy-all"
        dep.version = "3.0.9"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("3.0.9", result?.version)
        assertEquals("3.0", result?.majorMinor)
    }

    @Test
    fun `should detect Groovy from Maven groovy version property`() {
        val model = Model()
        model.properties.setProperty("groovy.version", "4.0.22")

        // Add a dependency that references the property
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "\${groovy.version}"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0.22", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should detect Groovy from Maven dependencyManagement`() {
        val model = Model()
        val depMgmt = org.apache.maven.model.DependencyManagement()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4.0.15"
        depMgmt.dependencies = listOf(dep)
        model.dependencyManagement = depMgmt

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0.15", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should detect Groovy from Gradle implementation declaration`(@TempDir tempDir: Path) {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            dependencies {
                implementation 'org.apache.groovy:groovy:4.0.15'
            }
            """.trimIndent(),
        )

        val result = detector.detectFromGradle(buildFile)

        assertNotNull(result)
        assertEquals("4.0.15", result?.version)
        assertEquals("4.0", result?.majorMinor)
        assertEquals("build.gradle", result?.source)
    }

    @Test
    fun `should detect Groovy from Gradle with double quotes`(@TempDir tempDir: Path) {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            dependencies {
                implementation "org.codehaus.groovy:groovy-all:3.0.9"
            }
            """.trimIndent(),
        )

        val result = detector.detectFromGradle(buildFile)

        assertNotNull(result)
        assertEquals("3.0.9", result?.version)
        assertEquals("3.0", result?.majorMinor)
    }

    @Test
    fun `should detect Groovy from Gradle groovyVersion property`(@TempDir tempDir: Path) {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            ext {
                groovyVersion = '4.0.22'
            }
            dependencies {
                implementation "org.apache.groovy:groovy:${'$'}groovyVersion"
            }
            """.trimIndent(),
        )

        val result = detector.detectFromGradle(buildFile)

        assertNotNull(result)
        assertEquals("4.0.22", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should detect Groovy from Gradle Kotlin DSL`(@TempDir tempDir: Path) {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            dependencies {
                implementation("org.apache.groovy:groovy:4.0.15")
            }
            """.trimIndent(),
        )

        val result = detector.detectFromGradle(buildFile)

        assertNotNull(result)
        assertEquals("4.0.15", result?.version)
        assertEquals("4.0", result?.majorMinor)
        assertEquals("build.gradle.kts", result?.source)
    }

    @Test
    fun `should return null when Groovy not found in Maven`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.junit.jupiter"
        dep.artifactId = "junit-jupiter"
        dep.version = "5.9.0"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNull(result)
    }

    @Test
    fun `should return null when Groovy not found in Gradle`(@TempDir tempDir: Path) {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            dependencies {
                testImplementation 'org.junit.jupiter:junit-jupiter:5.9.0'
            }
            """.trimIndent(),
        )

        val result = detector.detectFromGradle(buildFile)

        assertNull(result)
    }

    @Test
    fun `should extract majorMinor from full version`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4.0.15-beta-1"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0.15-beta-1", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should handle version with only major number`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should handle version with major and minor only`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4.0"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0", result?.version)
        assertEquals("4.0", result?.majorMinor)
    }

    @Test
    fun `should detect groovy-all artifact`() {
        val model = Model()
        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy-all"
        dep.version = "4.0.15"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        assertEquals("4.0.15", result?.version)
    }

    @Test
    fun `should prefer explicit dependency over property`() {
        val model = Model()
        model.properties.setProperty("groovy.version", "3.0.9")

        val dep = Dependency()
        dep.groupId = "org.apache.groovy"
        dep.artifactId = "groovy"
        dep.version = "4.0.15"
        model.dependencies = listOf(dep)

        val result = detector.detectFromMaven(model)

        assertNotNull(result)
        // Should use the explicit version, not the property
        assertEquals("4.0.15", result?.version)
    }
}
