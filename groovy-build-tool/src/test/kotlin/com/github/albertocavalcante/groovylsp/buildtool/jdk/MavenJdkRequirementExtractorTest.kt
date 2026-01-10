package com.github.albertocavalcante.groovylsp.buildtool.jdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MavenJdkRequirementExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private val extractor = MavenJdkRequirementExtractor()

    @Test
    fun `should extract release from maven-compiler-plugin`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.11.0</version>
                            <configuration>
                                <release>21</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, found.requirement.source)
    }

    @Test
    fun `should extract source and target from maven-compiler-plugin`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.11.0</version>
                            <configuration>
                                <source>11</source>
                                <target>17</target>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(11, found.requirement.sourceVersion)
        assertEquals(17, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_COMPILER_PLUGIN, found.requirement.source)
    }

    @Test
    fun `should extract from maven compiler properties`() {
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
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(11, found.requirement.sourceVersion)
        assertEquals(11, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY, found.requirement.source)
    }

    @Test
    fun `should extract from maven compiler release property`() {
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

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, found.requirement.source)
    }

    @Test
    fun `should handle 1_8 version format`() {
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
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(8, found.requirement.sourceVersion)
        assertEquals(8, found.requirement.targetVersion)
    }

    @Test
    fun `should extract from pluginManagement`() {
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

                <build>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <source>17</source>
                                    <target>17</target>
                                </configuration>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(17, found.requirement.targetVersion)
    }

    @Test
    fun `should return NotConfigured when no JDK config found`() {
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

                <dependencies>
                    <dependency>
                        <groupId>org.apache.groovy</groupId>
                        <artifactId>groovy</artifactId>
                        <version>4.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should return NotConfigured when no pom file exists`() {
        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `plugin config should take precedence over properties`() {
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
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <release>21</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Plugin release takes precedence
        assertEquals(21, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, found.requirement.source)
    }

    @Test
    fun `should extract from toolchains xml when toolchain plugin present`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-toolchains-plugin</artifactId>
                            <version>3.1.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        // Create .mvn/toolchains.xml
        val mvnDir = tempDir.resolve(".mvn")
        Files.createDirectories(mvnDir)
        val toolchainsFile = mvnDir.resolve("toolchains.xml")
        Files.writeString(
            toolchainsFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <toolchains>
                <toolchain>
                    <type>jdk</type>
                    <provides>
                        <version>21</version>
                        <vendor>temurin</vendor>
                    </provides>
                    <configuration>
                        <jdkHome>/path/to/jdk21</jdkHome>
                    </configuration>
                </toolchain>
            </toolchains>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.toolchainVersion)
        assertEquals(RequirementSource.MAVEN_TOOLCHAIN, found.requirement.source)
    }

    // Legacy Java 1.8 properties tests
    @Test
    fun `should extract legacy Java 1_8 properties`() {
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
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(8, found.requirement.sourceVersion)
        assertEquals(8, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY, found.requirement.source)
    }

    // Version parsing edge cases
    @Test
    fun `should parse version 1_8_0_292 with underscore build number`() {
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
                    <maven.compiler.source>1.8.0_292</maven.compiler.source>
                    <maven.compiler.target>1.8.0_292</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(8, found.requirement.sourceVersion)
        assertEquals(8, found.requirement.targetVersion)
    }

    @Test
    fun `should parse version 11_0_11 with minor version`() {
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
                    <maven.compiler.source>11.0.11</maven.compiler.source>
                    <maven.compiler.target>11.0.11</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(11, found.requirement.sourceVersion)
        assertEquals(11, found.requirement.targetVersion)
    }

    @Test
    fun `should parse version 17_0_1+12 with build number`() {
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
                    <maven.compiler.source>17.0.1+12</maven.compiler.source>
                    <maven.compiler.target>17.0.1+12</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(17, found.requirement.targetVersion)
    }

    @Test
    fun `should parse version 21-ea early access`() {
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
                    <maven.compiler.source>21-ea</maven.compiler.source>
                    <maven.compiler.target>21-ea</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.sourceVersion)
        assertEquals(21, found.requirement.targetVersion)
    }

    @Test
    fun `should parse version 21-ea+36 early access with build`() {
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
                    <maven.compiler.source>21-ea+36</maven.compiler.source>
                    <maven.compiler.target>21-ea+36</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(21, found.requirement.sourceVersion)
        assertEquals(21, found.requirement.targetVersion)
    }

    @Test
    fun `should handle empty string version`() {
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
                    <maven.compiler.source></maven.compiler.source>
                    <maven.compiler.target></maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Empty strings should result in NotConfigured
        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle whitespace only version`() {
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
                    <maven.compiler.source>   </maven.compiler.source>
                    <maven.compiler.target>   </maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Whitespace-only strings should result in NotConfigured
        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle invalid version string`() {
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
                    <maven.compiler.source>abc</maven.compiler.source>
                    <maven.compiler.target>xyz</maven.compiler.target>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Invalid version strings should result in NotConfigured
        assertTrue(result is JdkRequirementResult.NotConfigured)
    }

    // Multiple toolchains test
    @Test
    fun `should select first JDK toolchain from multiple toolchains`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-toolchains-plugin</artifactId>
                            <version>3.1.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val mvnDir = tempDir.resolve(".mvn")
        Files.createDirectories(mvnDir)
        val toolchainsFile = mvnDir.resolve("toolchains.xml")
        Files.writeString(
            toolchainsFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <toolchains>
                <toolchain>
                    <type>jdk</type>
                    <provides>
                        <version>11</version>
                        <vendor>temurin</vendor>
                    </provides>
                    <configuration>
                        <jdkHome>/path/to/jdk11</jdkHome>
                    </configuration>
                </toolchain>
                <toolchain>
                    <type>jdk</type>
                    <provides>
                        <version>21</version>
                        <vendor>temurin</vendor>
                    </provides>
                    <configuration>
                        <jdkHome>/path/to/jdk21</jdkHome>
                    </configuration>
                </toolchain>
            </toolchains>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Should select the first JDK toolchain
        assertEquals(11, found.requirement.toolchainVersion)
    }

    // XXE attack prevention test
    @Test
    fun `should reject XXE attack in toolchains xml`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-toolchains-plugin</artifactId>
                            <version>3.1.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val mvnDir = tempDir.resolve(".mvn")
        Files.createDirectories(mvnDir)
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

        val result = extractor.extract(tempDir)

        // XXE should be rejected, resulting in no toolchain version found
        // The extractor should handle this gracefully
        assertTrue(result is JdkRequirementResult.Found || result is JdkRequirementResult.NotConfigured)
        if (result is JdkRequirementResult.Found) {
            // If found, toolchainVersion should be null (XXE was blocked)
            assertEquals(null, result.requirement.toolchainVersion)
        }
    }

    // Malformed XML handling
    @Test
    fun `should handle invalid XML syntax gracefully`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <!-- Missing closing tags -->
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Should return ParseError for malformed XML
        assertTrue(result is JdkRequirementResult.ParseError || result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle empty pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(pomFile, "")

        val result = extractor.extract(tempDir)

        // Should handle empty file gracefully
        assertTrue(result is JdkRequirementResult.ParseError || result is JdkRequirementResult.NotConfigured)
    }

    @Test
    fun `should handle pom xml with missing closing tags`() {
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
                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                <!-- Missing closing tags for properties and project -->
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        // Should handle malformed XML gracefully
        assertTrue(result is JdkRequirementResult.ParseError || result is JdkRequirementResult.NotConfigured)
    }

    // Plugin in pluginManagement vs direct plugins
    @Test
    fun `should find config in pluginManagement when not in direct plugins`() {
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

                <build>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <source>17</source>
                                    <target>17</target>
                                </configuration>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(17, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_COMPILER_PLUGIN, found.requirement.source)
    }

    @Test
    fun `should prefer direct plugins over pluginManagement`() {
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

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <source>21</source>
                                <target>21</target>
                            </configuration>
                        </plugin>
                    </plugins>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <source>11</source>
                                    <target>11</target>
                                </configuration>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Should use direct plugins config (21), not pluginManagement (11)
        assertEquals(21, found.requirement.sourceVersion)
        assertEquals(21, found.requirement.targetVersion)
    }

    // Priority tests
    @Test
    fun `release property should take precedence over source and target properties`() {
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
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // release should take precedence
        assertEquals(17, found.requirement.targetVersion)
        assertEquals(11, found.requirement.sourceVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, found.requirement.source)
    }

    @Test
    fun `plugin release config should take precedence over property source and target`() {
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
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <release>21</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Plugin release should override property source/target
        assertEquals(21, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_RELEASE_PROPERTY, found.requirement.source)
    }

    @Test
    fun `plugin source and target should take precedence over properties`() {
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
                    <maven.compiler.source>8</maven.compiler.source>
                    <maven.compiler.target>8</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <source>17</source>
                                <target>17</target>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Plugin config should override properties
        assertEquals(17, found.requirement.sourceVersion)
        assertEquals(17, found.requirement.targetVersion)
        assertEquals(RequirementSource.MAVEN_COMPILER_PLUGIN, found.requirement.source)
    }

    // Real file from pipeline-library
    @Test
    fun `should extract from real pipeline-library pom xml`() {
        val pomFile = tempDir.resolve("pom.xml")
        Files.writeString(
            pomFile,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.jenkins.infra</groupId>
              <artifactId>pipeline-library</artifactId>
              <version>0.0.1</version>
              <name>Jenkins Pipeline Shared Library</name>
              <description>Pipeline Shared Library containing utility steps.</description>
              <url>https://github.com/jenkins-infra/pipeline-library</url>

              <licenses>
                <license>
                  <name>MIT License</name>
                  <url>http://opensource.org/licenses/MIT</url>
                </license>
              </licenses>

              <scm>
                <connection>scm:git:git://github.com/jenkins-infra/pipeline-library.git</connection>
                <developerConnection>scm:git:ssh://git@github.com/jenkins-infra/pipeline-library.git</developerConnection>
              </scm>

              <properties>
                <maven.compiler.source>1.8</maven.compiler.source>
                <maven.compiler.target>1.8</maven.compiler.target>
                <!-- Dependency versions -->
                <jenkins-pipeline-unit.version>1.30</jenkins-pipeline-unit.version>
                <groovy-eclipse-compiler.version>3.7.0</groovy-eclipse-compiler.version>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>org.codehaus.groovy</groupId>
                  <artifactId>groovy-all</artifactId>
                  <version>3.0.18</version>
                  <type>pom</type>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.codehaus.groovy</groupId>
                    <artifactId>groovy-eclipse-compiler</artifactId>
                    <version>3.7.0</version>
                    <extensions>true</extensions>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.1</version>
                    <configuration>
                      <compilerId>groovy-eclipse-compiler</compilerId>
                      <includes>
                        <include>src/**/*.groovy</include>
                        <include>test/**/*.groovy</include>
                        <include>vars/**.*.groovy</include>
                      </includes>
                    </configuration>
                    <dependencies>
                      <dependency>
                        <groupId>org.codehaus.groovy</groupId>
                        <artifactId>groovy-eclipse-batch</artifactId>
                        <version>3.0.8-01</version>
                      </dependency>
                      <dependency>
                        <groupId>org.codehaus.groovy</groupId>
                        <artifactId>groovy-eclipse-compiler</artifactId>
                        <version>3.7.0</version>
                      </dependency>
                    </dependencies>
                  </plugin>
                </plugins>
              </build>
            </project>
            """.trimIndent(),
        )

        val result = extractor.extract(tempDir)

        assertTrue(result is JdkRequirementResult.Found)
        val found = result as JdkRequirementResult.Found
        // Should extract Java 8 from legacy 1.8 properties
        assertEquals(8, found.requirement.sourceVersion)
        assertEquals(8, found.requirement.targetVersion)
        // The source should correctly report MAVEN_SOURCE_TARGET_PROPERTY since the actual
        // values come from properties (maven.compiler.source/target), not from plugin config.
        // The plugin only has compilerId config, not source/target/release.
        assertEquals(RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY, found.requirement.source)
    }
}
