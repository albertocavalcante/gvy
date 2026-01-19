package com.github.albertocavalcante.gvy.build.jdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GradleJdkRequirementExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private val extractor = GradleJdkRequirementExtractor()

    @Test
    fun `should extract toolchain languageVersion from Kotlin DSL`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            plugins {
                java
            }

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.toolchainVersion)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, found.requirement.source)
    }

    @Test
    fun `should extract toolchain with set syntax`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.toolchainVersion)
    }

    @Test
    fun `should extract sourceCompatibility with JavaVersion enum`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = JavaVersion.VERSION_17
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(RequirementSource.GRADLE_SOURCE_COMPATIBILITY, found.requirement.source)
    }

    @Test
    fun `should extract sourceCompatibility with string value`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = '11'
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(11, found.requirement.sourceVersion)
    }

    @Test
    fun `should extract sourceCompatibility with numeric value`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 17
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
    }

    @Test
    fun `should extract targetCompatibility`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            targetCompatibility = JavaVersion.VERSION_21
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.targetVersion)
    }

    @Test
    fun `should extract Java 1_8 format`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = JavaVersion.VERSION_1_8
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(8, found.requirement.sourceVersion)
    }

    @Test
    fun `should return NotConfigured when no JDK config found`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            plugins {
                id 'java'
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should return NotConfigured when no build file exists`() {
        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `toolchain should take precedence over sourceCompatibility`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                sourceCompatibility = JavaVersion.VERSION_11
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.effectiveVersion)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, found.requirement.source)
    }

    // EDGE CASE TESTS: Regex patterns

    @Test
    fun `should handle sourceCompatibility with inline comment`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 17 // Use Java 17
            targetCompatibility = 17 // Target Java 17
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
    }

    @Test
    fun `should handle multi-line toolchain block`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    // Multi-line configuration
                    languageVersion = JavaLanguageVersion.of(
                        21
                    )
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.toolchainVersion)
    }

    @Test
    fun `should handle Kotlin DSL with different spacing`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java{
                toolchain{
                    languageVersion=JavaLanguageVersion.of(17)
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.toolchainVersion)
    }

    @Test
    fun `should handle build file with very long lines`() {
        val buildFile = tempDir.resolve("build.gradle")
        val longComment = "// " + "a".repeat(10000)
        Files.writeString(
            buildFile,
            """
            $longComment
            sourceCompatibility = 17
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
    }

    // EDGE CASE TESTS: Version formats

    @Test
    fun `should extract JavaVersion VERSION_1_8 format`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(8, found.requirement.sourceVersion)
        assertEquals(8, found.requirement.targetVersion)
    }

    @Test
    fun `should extract JavaVersion VERSION_17 format`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = JavaVersion.VERSION_17
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
    }

    @Test
    fun `should extract JavaLanguageVersion of with spaces`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(   21   )
                }
            }
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.toolchainVersion)
    }

    @Test
    fun `should extract string version with quotes`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = "17"
            targetCompatibility = '21'
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(21, found.requirement.targetVersion)
    }

    @Test
    fun `should extract numeric version without quotes`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 17
            targetCompatibility = 21
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(21, found.requirement.targetVersion)
    }

    // EDGE CASE TESTS: Malformed files

    @Test
    fun `should handle missing closing braces`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                // Missing closing braces
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Should still extract the version even with malformed file
        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.toolchainVersion)
    }

    @Test
    fun `should handle truncated file`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility =
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle binary garbage content`() {
        val buildFile = tempDir.resolve("build.gradle")
        val binaryContent = ByteArray(1000) { it.toByte() }
        Files.write(buildFile, binaryContent)

        val result = extractor.extract(tempDir)

        // Should not crash, should return NotConfigured or ParseError
        assertTrue(result is JdkRequirementResult.NotConfigured || result is JdkRequirementResult.ParseError)
    }

    @Test
    fun `should handle empty file`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(buildFile, "")

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }
}
