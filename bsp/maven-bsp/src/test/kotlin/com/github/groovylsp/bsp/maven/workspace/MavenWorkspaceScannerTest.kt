package com.github.groovylsp.bsp.maven.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * TDD tests for MavenWorkspaceScanner.
 *
 * These tests verify:
 * - Single-module project detection
 * - Multi-module project detection
 * - POM parsing (coordinates, dependencies, properties)
 * - Parent-child inheritance
 * - Edge cases and error handling
 */
class MavenWorkspaceScannerTest {

    private lateinit var scanner: MavenWorkspaceScanner

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        scanner = MavenWorkspaceScanner()
    }

    @Nested
    inner class SingleModuleProjects {

        @Test
        fun `should detect single-module Maven project`() {
            // Given: A directory with a simple pom.xml
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When: Scanning the workspace
            val modules = scanner.scan(tempDir)

            // Then: Should find exactly one module
            assertThat(modules).hasSize(1)
            val module = modules.first()
            assertThat(module.groupId).isEqualTo("com.example")
            assertThat(module.artifactId).isEqualTo("my-app")
            assertThat(module.version).isEqualTo("1.0.0")
            assertThat(module.packaging).isEqualTo("jar") // default
        }

        @Test
        fun `should extract module coordinates from pom`() {
            // Given: A pom with full coordinates
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>pipeline-library</artifactId>
                    <version>2.5.0-SNAPSHOT</version>
                    <packaging>hpi</packaging>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            assertThat(module!!.groupId).isEqualTo("org.jenkins-ci.plugins")
            assertThat(module.artifactId).isEqualTo("pipeline-library")
            assertThat(module.version).isEqualTo("2.5.0-SNAPSHOT")
            assertThat(module.packaging).isEqualTo("hpi")
            assertThat(module.coordinates).isEqualTo("org.jenkins-ci.plugins:pipeline-library:2.5.0-SNAPSHOT")
        }

        @Test
        fun `should parse dependencies from pom`() {
            // Given: A pom with dependencies
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.groovy</groupId>
                            <artifactId>groovy</artifactId>
                            <version>4.0.23</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            assertThat(module!!.dependencies).hasSize(2)

            val groovyDep = module.dependencies.find { it.artifactId == "groovy" }
            assertThat(groovyDep).isNotNull
            assertThat(groovyDep!!.groupId).isEqualTo("org.apache.groovy")
            assertThat(groovyDep.version).isEqualTo("4.0.23")
            assertThat(groovyDep.scope).isEqualTo("compile") // default

            val junitDep = module.dependencies.find { it.artifactId == "junit" }
            assertThat(junitDep).isNotNull
            assertThat(junitDep!!.scope).isEqualTo("test")
        }

        @Test
        fun `should handle custom source directories`() {
            // Given: A pom with custom source directory
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <sourceDirectory>src/main/groovy</sourceDirectory>
                        <testSourceDirectory>src/test/groovy</testSourceDirectory>
                    </build>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            assertThat(module!!.sourceDirectory).isEqualTo("src/main/groovy")
            assertThat(module.testSourceDirectory).isEqualTo("src/test/groovy")
        }
    }

    @Nested
    inner class MultiModuleProjects {

        @Test
        fun `should detect multi-module Maven project`() {
            // Given: A multi-module project structure
            createMultiModuleProject()

            // When
            val modules = scanner.scan(tempDir)

            // Then: Should find parent + 2 child modules
            assertThat(modules).hasSize(3)

            val parent = modules.find { it.artifactId == "parent-project" }
            assertThat(parent).isNotNull
            assertThat(parent!!.isAggregator).isTrue()
            assertThat(parent.modules).containsExactlyInAnyOrder("module-a", "module-b")

            val moduleA = modules.find { it.artifactId == "module-a" }
            assertThat(moduleA).isNotNull
            assertThat(moduleA!!.groupId).isEqualTo("com.example")

            val moduleB = modules.find { it.artifactId == "module-b" }
            assertThat(moduleB).isNotNull
        }

        @Test
        fun `should handle parent-child pom inheritance`() {
            // Given: A child module inheriting from parent
            val parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(parentPom)

            // Child inherits groupId and version from parent
            val childDir = tempDir.resolve("child").createDirectories()
            val childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
            """.trimIndent()
            childDir.resolve("pom.xml").writeText(childPom)

            // When
            val modules = scanner.scan(tempDir)

            // Then: Child should inherit groupId and version
            val child = modules.find { it.artifactId == "child" }
            assertThat(child).isNotNull
            assertThat(child!!.groupId).isEqualTo("com.example") // Inherited
            assertThat(child.version).isEqualTo("1.0.0") // Inherited
            assertThat(child.parent).isNotNull
            assertThat(child.parent!!.artifactId).isEqualTo("parent")
        }

        @Test
        fun `should handle nested multi-module projects`() {
            // Given: A nested multi-module structure (parent -> subparent -> leaf)
            val rootPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>sub</module>
                    </modules>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(rootPom)

            val subDir = tempDir.resolve("sub").createDirectories()
            val subPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>sub</artifactId>
                    <packaging>pom</packaging>
                    <modules>
                        <module>leaf</module>
                    </modules>
                </project>
            """.trimIndent()
            subDir.resolve("pom.xml").writeText(subPom)

            val leafDir = subDir.resolve("leaf").createDirectories()
            val leafPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>sub</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>leaf</artifactId>
                </project>
            """.trimIndent()
            leafDir.resolve("pom.xml").writeText(leafPom)

            // When
            val modules = scanner.scan(tempDir)

            // Then
            assertThat(modules).hasSize(3)
            assertThat(modules.map { it.artifactId }).containsExactlyInAnyOrder("root", "sub", "leaf")

            val leaf = modules.find { it.artifactId == "leaf" }
            assertThat(leaf!!.groupId).isEqualTo("com.example") // Inherited through hierarchy
        }

        private fun createMultiModuleProject() {
            val parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent-project</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(parentPom)

            val moduleADir = tempDir.resolve("module-a").createDirectories()
            val moduleAPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-project</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-a</artifactId>
                </project>
            """.trimIndent()
            moduleADir.resolve("pom.xml").writeText(moduleAPom)

            val moduleBDir = tempDir.resolve("module-b").createDirectories()
            val moduleBPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-project</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-b</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>module-a</artifactId>
                            <version>${"$"}{project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            moduleBDir.resolve("pom.xml").writeText(moduleBPom)
        }
    }

    @Nested
    inner class PropertyInterpolation {

        @Test
        fun `should resolve properties in version`() {
            // Given: A pom using properties for version
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>${"$"}{revision}</version>
                    <properties>
                        <revision>2.0.0</revision>
                    </properties>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then: Property should be resolved
            assertThat(module).isNotNull
            assertThat(module!!.version).isEqualTo("2.0.0")
        }

        @Test
        fun `should resolve properties in dependency versions`() {
            // Given: A pom with property-based dependency versions
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <groovy.version>4.0.23</groovy.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.groovy</groupId>
                            <artifactId>groovy</artifactId>
                            <version>${"$"}{groovy.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            val groovyDep = module!!.dependencies.find { it.artifactId == "groovy" }
            assertThat(groovyDep).isNotNull
            assertThat(groovyDep!!.version).isEqualTo("4.0.23")
        }

        @Test
        fun `should resolve project version reference`() {
            // Given: A dependency using ${project.version}
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.5.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>sibling</artifactId>
                            <version>${"$"}{project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            val siblingDep = module!!.dependencies.find { it.artifactId == "sibling" }
            assertThat(siblingDep!!.version).isEqualTo("1.5.0")
        }
    }

    @Nested
    inner class EdgeCasesAndErrorHandling {

        @Test
        fun `should return empty list for non-Maven directory`() {
            // Given: A directory without pom.xml

            // When
            val modules = scanner.scan(tempDir)

            // Then
            assertThat(modules).isEmpty()
        }

        @Test
        fun `should return null for invalid pom`() {
            // Given: An invalid pom.xml
            tempDir.resolve("pom.xml").writeText("not valid xml")

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNull()
        }

        @Test
        fun `should return null for non-existent pom`() {
            // When
            val module = scanner.parseModule(tempDir.resolve("non-existent.xml"))

            // Then
            assertThat(module).isNull()
        }

        @Test
        fun `should skip non-Maven directories when scanning modules`() {
            // Given: A multi-module project with a non-Maven module reference
            val parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>valid-module</module>
                        <module>not-a-maven-dir</module>
                    </modules>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(parentPom)

            // Create valid module
            val validDir = tempDir.resolve("valid-module").createDirectories()
            val validPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>valid-module</artifactId>
                </project>
            """.trimIndent()
            validDir.resolve("pom.xml").writeText(validPom)

            // Create invalid directory (no pom.xml)
            tempDir.resolve("not-a-maven-dir").createDirectories()

            // When
            val modules = scanner.scan(tempDir)

            // Then: Should find parent and valid module, skip invalid
            assertThat(modules).hasSize(2)
            assertThat(modules.map { it.artifactId }).containsExactlyInAnyOrder("parent", "valid-module")
        }

        @Test
        fun `should handle empty dependencies list`() {
            // Given: A pom with no dependencies
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>no-deps</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            assertThat(module!!.dependencies).isEmpty()
        }

        @Test
        fun `should handle dependency with classifier`() {
            // Given: A dependency with classifier
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>some-lib</artifactId>
                            <version>2.0.0</version>
                            <classifier>sources</classifier>
                            <type>jar</type>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            val dep = module!!.dependencies.first()
            assertThat(dep.classifier).isEqualTo("sources")
            assertThat(dep.type).isEqualTo("jar")
        }

        @Test
        fun `should handle optional dependencies`() {
            // Given: An optional dependency
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>optional-lib</artifactId>
                            <version>1.0.0</version>
                            <optional>true</optional>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            val dep = module!!.dependencies.first()
            assertThat(dep.optional).isTrue()
        }

        @Test
        fun `should handle pom with dependencyManagement section`() {
            // Given: A pom with local dependencyManagement (no import scope)
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.groovy</groupId>
                                <artifactId>groovy</artifactId>
                                <version>4.0.23</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.groovy</groupId>
                            <artifactId>groovy</artifactId>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then: Should parse successfully (version from dependencyManagement)
            assertThat(module).isNotNull
            val groovyDep = module!!.dependencies.find { it.artifactId == "groovy" }
            assertThat(groovyDep).isNotNull
            // Version is resolved from dependencyManagement
            assertThat(groovyDep!!.version).isEqualTo("4.0.23")
        }
    }

    @Nested
    inner class JenkinsStyleProjects {

        @Test
        fun `should handle Jenkins plugin project with local parent`() {
            // Given: A Jenkins-style plugin project with local parent pom
            // Create a local parent pom (simulating Jenkins plugin parent)
            val parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>plugin-parent</artifactId>
                    <version>4.85</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>my-plugin</module>
                    </modules>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(parentPom)

            // Create plugin module
            val pluginDir = tempDir.resolve("my-plugin").createDirectories()
            val pluginPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.jenkins-ci.plugins</groupId>
                        <artifactId>plugin-parent</artifactId>
                        <version>4.85</version>
                    </parent>
                    <artifactId>my-jenkins-plugin</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <packaging>hpi</packaging>
                    <properties>
                        <jenkins.version>2.426.3</jenkins.version>
                    </properties>
                </project>
            """.trimIndent()
            pluginDir.resolve("pom.xml").writeText(pluginPom)

            // When
            val modules = scanner.scan(tempDir)

            // Then
            assertThat(modules).hasSize(2)

            val plugin = modules.find { it.artifactId == "my-jenkins-plugin" }
            assertThat(plugin).isNotNull
            assertThat(plugin!!.packaging).isEqualTo("hpi")
            assertThat(plugin.parent).isNotNull
            assertThat(plugin.parent!!.artifactId).isEqualTo("plugin-parent")
            assertThat(plugin.groupId).isEqualTo("org.jenkins-ci.plugins") // Inherited
        }

        @Test
        fun `should handle HPI packaging type`() {
            // Given: A self-contained pom with HPI packaging (Jenkins plugin archive)
            val pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>my-jenkins-plugin</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <packaging>hpi</packaging>
                </project>
            """.trimIndent()
            tempDir.resolve("pom.xml").writeText(pomContent)

            // When
            val module = scanner.parseModule(tempDir.resolve("pom.xml"))

            // Then
            assertThat(module).isNotNull
            assertThat(module!!.packaging).isEqualTo("hpi")
            assertThat(module.groupId).isEqualTo("org.jenkins-ci.plugins")
        }
    }
}
