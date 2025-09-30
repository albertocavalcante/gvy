package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.gradle.GradleSourceSetResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for compilation context classpath configuration.
 * These tests verify that compilation contexts properly include source directories
 * in their classpaths for symbol resolution.
 *
 * Currently FAILING - these tests expose the missing source directory classpath issues.
 */
class CompilationContextClasspathTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var contextManager: CompilationContextManager
    private lateinit var gradleSourceSetResolver: GradleSourceSetResolver

    @BeforeEach
    fun setUp() {
        gradleSourceSetResolver = GradleSourceSetResolver()
        contextManager = CompilationContextManager(gradleSourceSetResolver)
    }

    @Test
    fun `main context classpath should include src main groovy directory`() = runTest {
        // Given: A Gradle project with main source directory
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        Files.createDirectories(mainSrcDir)

        // Create a sample class to ensure directory is recognized
        val sampleClassContent = """
            package com.example
            class SampleClass { }
        """.trimIndent()
        Files.writeString(mainSrcDir.resolve("SampleClass.groovy"), sampleClassContent)

        // When: Build compilation contexts
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Main context should include src/main/groovy in classpath
        val mainContext = contexts["main"]
        assertNotNull(mainContext, "Should have main compilation context")

        assertTrue(
            mainContext.classpath.contains(mainSrcDir),
            "Main context classpath should include src/main/groovy. " +
                "Classpath: ${mainContext.classpath}, " +
                "Expected to contain: $mainSrcDir",
        )
    }

    @Test
    fun `test context classpath should include both test and main source directories`() = runTest {
        // Given: A Gradle project with both main and test source directories
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        val testSrcDir = projectRoot.resolve("src/test/groovy")
        Files.createDirectories(mainSrcDir)
        Files.createDirectories(testSrcDir)

        // Create sample classes
        Files.writeString(mainSrcDir.resolve("MainClass.groovy"), "class MainClass { }")
        Files.writeString(testSrcDir.resolve("TestClass.groovy"), "class TestClass { }")

        // When: Build compilation contexts
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Test context should include both directories
        val testContext = contexts["test"]
        assertNotNull(testContext, "Should have test compilation context")

        assertTrue(
            testContext.classpath.contains(testSrcDir),
            "Test context classpath should include src/test/groovy. " +
                "Classpath: ${testContext.classpath}",
        )

        assertTrue(
            testContext.classpath.contains(mainSrcDir),
            "Test context classpath should include src/main/groovy for dependencies. " +
                "Classpath: ${testContext.classpath}",
        )
    }

    @Test
    fun `fallback context should include all discovered groovy directories`() = runTest {
        // Given: A project without proper Gradle structure (fallback scenario)
        val projectRoot = tempDir.resolve("non-gradle-project")
        Files.createDirectories(projectRoot)

        // Create some Groovy files in various locations
        val srcDir = projectRoot.resolve("src/main/groovy")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("ClassA.groovy"), "class ClassA { }")

        // When: Build contexts (should fall back to workspace context)
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Workspace context should include source directories
        val workspaceContext = contexts["workspace"]
        assertNotNull(workspaceContext, "Should have workspace fallback context")

        assertTrue(
            workspaceContext.classpath.contains(srcDir),
            "Workspace context classpath should include discovered source directory. " +
                "Classpath: ${workspaceContext.classpath}",
        )
    }

    @Test
    fun `compilation context should resolve file to correct context`() = runTest {
        // Given: A project with files in different source sets
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        val testSrcDir = projectRoot.resolve("src/test/groovy")
        Files.createDirectories(mainSrcDir)
        Files.createDirectories(testSrcDir)

        val mainClassFile = mainSrcDir.resolve("MainClass.groovy")
        val testClassFile = testSrcDir.resolve("TestClass.groovy")

        Files.writeString(mainClassFile, "class MainClass { }")
        Files.writeString(testClassFile, "class TestClass { }")

        // When: Build contexts
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Files should resolve to correct contexts
        val mainFileContext = contextManager.getContextForFile(mainClassFile.toUri())
        val testFileContext = contextManager.getContextForFile(testClassFile.toUri())

        assertTrue(
            mainFileContext == "main",
            "Main class file should resolve to main context. Got: $mainFileContext",
        )

        assertTrue(
            testFileContext == "test",
            "Test class file should resolve to test context. Got: $testFileContext",
        )
    }

    @Test
    fun `context dependencies should be properly configured`() = runTest {
        // Given: A project with main and test source sets
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        val testSrcDir = projectRoot.resolve("src/test/groovy")
        Files.createDirectories(mainSrcDir)
        Files.createDirectories(testSrcDir)

        Files.writeString(mainSrcDir.resolve("MainClass.groovy"), "class MainClass { }")
        Files.writeString(testSrcDir.resolve("TestClass.groovy"), "class TestClass { }")

        // When: Build contexts
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Test context should depend on main context
        val testContext = contexts["test"]
        assertNotNull(testContext, "Should have test context")

        assertTrue(
            testContext.dependencies.contains("main"),
            "Test context should depend on main context. Dependencies: ${testContext.dependencies}",
        )

        // And dependency resolution should work
        val testDependencies = contextManager.getDependenciesForContext("test")
        val mainDependency = testDependencies.find { it.name == "main" }

        assertNotNull(mainDependency, "Should resolve main context as dependency of test")
        assertTrue(
            mainDependency.classpath.contains(mainSrcDir),
            "Main dependency should include its source directory in classpath",
        )
    }

    @Test
    fun `source set resolver should create contexts with source directories in classpath`() {
        // Given: A project with conventional Gradle structure
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        Files.createDirectories(mainSrcDir)

        // When: Resolve source sets using GradleSourceSetResolver
        val sourceSets = gradleSourceSetResolver.resolveSourceSets(projectRoot)

        // Then: Source sets should have their directories in compile classpath
        val mainSourceSet = sourceSets.find { it.name == "main" }
        assertNotNull(mainSourceSet, "Should have main source set")

        assertTrue(
            mainSourceSet.compileClasspath.isNotEmpty(),
            "Main source set should have non-empty compile classpath",
        )

        // Check if source directory itself is in classpath for symbol resolution
        assertTrue(
            mainSourceSet.sourceDirs.any { sourceDir ->
                mainSourceSet.compileClasspath.contains(sourceDir)
            },
            "Source set classpath should include at least one of its source directories. " +
                "Source dirs: ${mainSourceSet.sourceDirs}, " +
                "Classpath: ${mainSourceSet.compileClasspath}",
        )
    }

    @Test
    fun `context should contain all files in its source directories`() = runTest {
        // Given: A project with multiple files in source directories
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy/com/example")
        Files.createDirectories(mainSrcDir)

        val file1 = mainSrcDir.resolve("ClassA.groovy")
        val file2 = mainSrcDir.resolve("ClassB.groovy")
        val file3 = mainSrcDir.resolve("ClassC.groovy")

        Files.writeString(file1, "package com.example; class ClassA { }")
        Files.writeString(file2, "package com.example; class ClassB { }")
        Files.writeString(file3, "package com.example; class ClassC { }")

        // When: Build contexts
        val contexts = contextManager.buildContexts(projectRoot)

        // Then: Main context should contain all files
        val mainContext = contexts["main"]
        assertNotNull(mainContext, "Should have main context")

        assertTrue(
            mainContext.containsFile(file1.toUri()),
            "Main context should contain ClassA.groovy",
        )
        assertTrue(
            mainContext.containsFile(file2.toUri()),
            "Main context should contain ClassB.groovy",
        )
        assertTrue(
            mainContext.containsFile(file3.toUri()),
            "Main context should contain ClassC.groovy",
        )

        assertTrue(
            mainContext.fileCount() >= 3,
            "Main context should contain at least 3 files. Count: ${mainContext.fileCount()}",
        )
    }

    /**
     * Creates a basic Gradle project structure
     */
    private fun createGradleProject(): Path {
        val buildGradleContent = """
            plugins {
                id 'groovy'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.apache.groovy:groovy:4.0.28'
            }
        """.trimIndent()

        val buildFile = tempDir.resolve("build.gradle")
        Files.writeString(buildFile, buildGradleContent)

        return tempDir
    }

    private suspend fun CompilationContextManager.buildContexts(root: Path) =
        kotlinx.coroutines.runBlocking { buildContexts(root) }
}
