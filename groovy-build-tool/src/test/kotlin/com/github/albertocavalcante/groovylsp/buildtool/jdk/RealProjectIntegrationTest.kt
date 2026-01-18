package com.github.albertocavalcante.groovylsp.buildtool.jdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Integration tests that run against real projects on the filesystem.
 * These tests are skipped if the test projects don't exist.
 */
class RealProjectIntegrationTest {

    private val pipelineLibraryPath = Path.of("/Users/adsc/dev/refs/pipeline-library")

    private fun pipelineLibraryExists(): Boolean = pipelineLibraryPath.exists()

    @Test
    @EnabledIf("pipelineLibraryExists")
    fun `should extract JDK 8 from pipeline-library Maven project`() {
        val extractor = MavenJdkRequirementExtractor()
        val result = extractor.extract(pipelineLibraryPath)

        assertTrue(result is JdkRequirementResult.Found, "Expected Found but got $result")
        val found = result as JdkRequirementResult.Found

        assertEquals(8, found.requirement.sourceVersion, "Expected sourceVersion=8")
        assertEquals(8, found.requirement.targetVersion, "Expected targetVersion=8")
        assertEquals(
            RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY,
            found.requirement.source,
            "Expected source from properties, not plugin",
        )
    }

    @Test
    @EnabledIf("pipelineLibraryExists")
    fun `should validate JDK compatibility for pipeline-library`() {
        val validator = ProjectJdkValidator()
        val result = validator.validate(pipelineLibraryPath, runningJdk = 21)

        // Running JDK 21 should trigger PotentiallyIncompatibleNewer since project targets JDK 8
        assertTrue(
            result is ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer,
            "Expected PotentiallyIncompatibleNewer but got $result",
        )

        val warning = result as ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer
        assertEquals(21, warning.runningJdk)
        assertEquals(8, warning.targetJdk)
    }

    @Test
    @EnabledIf("pipelineLibraryExists")
    fun `should show Compatible when running matching JDK version`() {
        val validator = ProjectJdkValidator()

        // Running JDK 8 should be compatible with project targeting JDK 8
        val result = validator.validate(pipelineLibraryPath, runningJdk = 8)

        assertTrue(
            result is ProjectJdkValidator.ValidationResult.Compatible,
            "Expected Compatible but got $result",
        )

        val compatible = result as ProjectJdkValidator.ValidationResult.Compatible
        assertEquals(8, compatible.runningJdk)
        assertEquals(8, compatible.requiredJdk)
    }

    @Test
    @EnabledIf("pipelineLibraryExists")
    fun `should show IncompatibleOlder when running older JDK`() {
        val validator = ProjectJdkValidator()

        // Running JDK 6 should be incompatible with project targeting JDK 8
        val result = validator.validate(pipelineLibraryPath, runningJdk = 6)

        assertTrue(
            result is ProjectJdkValidator.ValidationResult.IncompatibleOlder,
            "Expected IncompatibleOlder but got $result",
        )

        val incompatible = result as ProjectJdkValidator.ValidationResult.IncompatibleOlder
        assertEquals(6, incompatible.runningJdk)
        assertEquals(8, incompatible.requiredJdk)
    }
}
