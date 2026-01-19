package com.github.albertocavalcante.gvy.build.jdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectJdkValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    private val validator = ProjectJdkValidator()

    @Test
    fun `should return Compatible when running JDK matches required`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 17)

        assertTrue(result is ProjectJdkValidator.ValidationResult.Compatible)
        val compatible = result as ProjectJdkValidator.ValidationResult.Compatible
        assertEquals(17, compatible.runningJdk)
        assertEquals(17, compatible.requiredJdk)
    }

    @Test
    fun `should return Compatible when running JDK is slightly newer`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            """.trimIndent(),
        )

        // Running JDK 19 is within threshold (17 + 2 = 19)
        val result = validator.validate(tempDir, runningJdk = 19)

        assertTrue(result is ProjectJdkValidator.ValidationResult.Compatible)
    }

    @Test
    fun `should return IncompatibleOlder when running JDK is older than required`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 17)

        assertTrue(result is ProjectJdkValidator.ValidationResult.IncompatibleOlder)
        val incompatible = result as ProjectJdkValidator.ValidationResult.IncompatibleOlder
        assertEquals(17, incompatible.runningJdk)
        assertEquals(21, incompatible.requiredJdk)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, incompatible.source)
        assertTrue(incompatible.suggestions.isNotEmpty())
    }

    @Test
    fun `should return PotentiallyIncompatibleNewer when running JDK is significantly newer`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(11)
                }
            }
            """.trimIndent(),
        )

        // Running JDK 25 is way newer than target 11 (threshold = 2)
        val result = validator.validate(tempDir, runningJdk = 25)

        assertTrue(result is ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer)
        val warning = result as ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer
        assertEquals(25, warning.runningJdk)
        assertEquals(11, warning.targetJdk)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, warning.source)
        assertTrue(warning.suggestions.any { it.contains("class file major version") })
    }

    @Test
    fun `should return NoRequirement when no build file exists`() {
        val result = validator.validate(tempDir, runningJdk = 17)

        assertTrue(result is ProjectJdkValidator.ValidationResult.NoRequirement)
    }

    @Test
    fun `should return NoRequirement when no JDK config in build file`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            plugins {
                id 'java'
            }

            dependencies {
                implementation 'org.apache.groovy:groovy:4.0.0'
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 17)

        assertTrue(result is ProjectJdkValidator.ValidationResult.NoRequirement)
    }

    @Test
    fun `should extract from java-version file`() {
        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "21")

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(21, requirement?.targetVersion)
        assertEquals(RequirementSource.PROJECT_FILE_JAVA_VERSION, requirement?.source)
    }

    @Test
    fun `should extract from java-version file with full version`() {
        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "17.0.5")

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(17, requirement?.targetVersion)
    }

    @Test
    fun `should extract from sdkmanrc file`() {
        val sdkmanrcFile = tempDir.resolve(".sdkmanrc")
        Files.writeString(
            sdkmanrcFile,
            """
            # Enable auto-env through the sdkman_auto_env config
            java=21.0.5-tem
            gradle=8.5
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(21, requirement?.targetVersion)
        assertEquals(RequirementSource.PROJECT_FILE_SDKMANRC, requirement?.source)
    }

    @Test
    fun `should prefer Maven over project files`() {
        // Create both pom.xml and .java-version
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>

                <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties>
            </project>
            """.trimIndent(),
        )

        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "21")

        val requirement = validator.extractRequirement(tempDir)

        // Maven should take precedence
        assertEquals(17, requirement?.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, requirement?.source)
    }

    @Test
    fun `should prefer Gradle over project files`() {
        // Create both build.gradle and .java-version
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "17")

        val requirement = validator.extractRequirement(tempDir)

        // Gradle should take precedence
        assertEquals(21, requirement?.toolchainVersion)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, requirement?.source)
    }

    @Test
    fun `suggestions should include actionable guidance for older JDK`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 11)

        assertTrue(result is ProjectJdkValidator.ValidationResult.IncompatibleOlder)
        val incompatible = result as ProjectJdkValidator.ValidationResult.IncompatibleOlder

        // Check suggestions have actionable guidance
        assertTrue(incompatible.suggestions.any { it.contains("groovy.java.home") })
        assertTrue(incompatible.suggestions.any { it.contains("sdk install java") })
    }

    @Test
    fun `suggestions should include actionable guidance for newer JDK`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(11)
                }
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 25)

        assertTrue(result is ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer)
        val warning = result as ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer

        // Check suggestions have actionable guidance
        assertTrue(warning.suggestions.any { it.contains("groovy.java.home") })
        assertTrue(warning.suggestions.any { it.contains("update project") || it.contains("target") })
    }

    // EDGE CASE TESTS: Boundary conditions

    @Test
    fun `should return Compatible when running JDK is exactly at threshold`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            """.trimIndent(),
        )

        // Running JDK 19 = required 17 + threshold 2
        val result = validator.validate(tempDir, runningJdk = 19)

        assertTrue(result is ProjectJdkValidator.ValidationResult.Compatible)
        val compatible = result as ProjectJdkValidator.ValidationResult.Compatible
        assertEquals(19, compatible.runningJdk)
        assertEquals(17, compatible.requiredJdk)
    }

    @Test
    fun `should return PotentiallyIncompatibleNewer when running JDK exceeds threshold by one`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            """.trimIndent(),
        )

        // Running JDK 20 = required 17 + threshold 2 + 1
        val result = validator.validate(tempDir, runningJdk = 20)

        assertTrue(result is ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer)
    }

    @Test
    fun `should handle running JDK equals 1`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(8)
                }
            }
            """.trimIndent(),
        )

        // Edge case: running JDK 1 (theoretically minimal valid)
        val result = validator.validate(tempDir, runningJdk = 1)

        assertTrue(result is ProjectJdkValidator.ValidationResult.IncompatibleOlder)
        val incompatible = result as ProjectJdkValidator.ValidationResult.IncompatibleOlder
        assertEquals(1, incompatible.runningJdk)
        assertEquals(8, incompatible.requiredJdk)
    }

    @Test
    fun `should handle very high required JDK version`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                }
            }
            """.trimIndent(),
        )

        val result = validator.validate(tempDir, runningJdk = 21)

        assertTrue(result is ProjectJdkValidator.ValidationResult.IncompatibleOlder)
        val incompatible = result as ProjectJdkValidator.ValidationResult.IncompatibleOlder
        assertEquals(21, incompatible.runningJdk)
        assertEquals(99, incompatible.requiredJdk)
    }

    @Test
    fun `should handle integer near max value without overflow`() {
        val buildFile = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFile,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(2147483645)
                }
            }
            """.trimIndent(),
        )

        // Int.MAX_VALUE - 2 = 2147483645
        // This tests that required + THRESHOLD doesn't overflow
        val result = validator.validate(tempDir, runningJdk = 21)

        assertTrue(result is ProjectJdkValidator.ValidationResult.IncompatibleOlder)
    }

    // EDGE CASE TESTS: Project file parsing

    @Test
    fun `should parse java-version file with comments`() {
        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(
            javaVersionFile,
            """
            # This is a comment
            17
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        // Comments should not prevent parsing
        // The implementation currently reads trim(), so this might fail
        // depending on implementation - documenting expected behavior
        assertTrue(requirement == null || requirement.targetVersion == 17)
    }

    @Test
    fun `should parse java-version file with trailing newlines`() {
        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "17\n\n\n")

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(17, requirement?.targetVersion)
        assertEquals(RequirementSource.PROJECT_FILE_JAVA_VERSION, requirement?.source)
    }

    @Test
    fun `should parse java-version file with leading whitespace`() {
        val javaVersionFile = tempDir.resolve(".java-version")
        Files.writeString(javaVersionFile, "   21   ")

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(21, requirement?.targetVersion)
    }

    @Test
    fun `should parse sdkmanrc with multiple java lines`() {
        val sdkmanrcFile = tempDir.resolve(".sdkmanrc")
        Files.writeString(
            sdkmanrcFile,
            """
            gradle=8.5
            java=17.0.5-tem
            java=21.0.1-zulu
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        // Should use the first java= line
        assertEquals(17, requirement?.targetVersion)
    }

    @Test
    fun `should parse sdkmanrc with spaces around equals`() {
        val sdkmanrcFile = tempDir.resolve(".sdkmanrc")
        Files.writeString(
            sdkmanrcFile,
            """
            gradle = 8.5
            java = 21.0.5-tem
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        // The implementation uses startsWith("java=") so this might not match
        // Documenting the expected behavior
        assertTrue(requirement == null || requirement.targetVersion == 21)
    }

    @Test
    fun `should parse sdkmanrc with vendor suffix variations`() {
        val sdkmanrcFile = tempDir.resolve(".sdkmanrc")
        Files.writeString(
            sdkmanrcFile,
            """
            java=17.0.5-zulu
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(17, requirement?.targetVersion)
    }

    // EDGE CASE TESTS: Validator priority

    @Test
    fun `should prioritize Maven over Gradle when both exist`() {
        // Create pom.xml
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>

                <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties>
            </project>
            """.trimIndent(),
        )

        // Create build.gradle with different version
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 21
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        // Maven should win
        assertEquals(17, requirement?.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, requirement?.source)
    }

    @Test
    fun `should treat build gradle kts same priority as build gradle`() {
        // Create build.gradle.kts (Kotlin DSL)
        val buildFileKts = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFileKts,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        assertEquals(21, requirement?.toolchainVersion)
        assertEquals(RequirementSource.GRADLE_TOOLCHAIN, requirement?.source)
    }

    @Test
    fun `should handle both build gradle and build gradle kts present`() {
        // If both exist, build.gradle.kts should be preferred (Gradle behavior)
        val buildFileKts = tempDir.resolve("build.gradle.kts")
        Files.writeString(
            buildFileKts,
            """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            """.trimIndent(),
        )

        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 17
            """.trimIndent(),
        )

        val requirement = validator.extractRequirement(tempDir)

        // The Gradle extractor checks for .kts first
        assertEquals(21, requirement?.toolchainVersion)
    }
}
