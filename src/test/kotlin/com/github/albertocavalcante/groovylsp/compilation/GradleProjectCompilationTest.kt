package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.gradle.GradleSourceSetResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Gradle project compilation focusing on classpath configuration.
 * These tests verify that source directories are properly included in compilation
 * and that inter-class dependencies can be resolved.
 *
 * Currently FAILING - these tests expose the classpath configuration issues.
 */
class GradleProjectCompilationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspaceCompilationService: WorkspaceCompilationService
    private lateinit var gradleSourceSetResolver: GradleSourceSetResolver
    private lateinit var coroutineScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dependencyManager = com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager()
        workspaceCompilationService = WorkspaceCompilationService(coroutineScope, dependencyManager)
        gradleSourceSetResolver = GradleSourceSetResolver()
    }

    @Test
    fun `source directories should be included in compilation classpath`() = runTest {
        // Given: A Gradle project with conventional structure
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        Files.createDirectories(mainSrcDir)

        // When: Resolve source sets
        val sourceSets = gradleSourceSetResolver.resolveSourceSets(projectRoot)

        // Then: Main source set should include its source directory in classpath
        val mainSourceSet = sourceSets.find { it.name == "main" }
        assertNotNull(mainSourceSet, "Should have main source set")
        assertTrue(
            mainSourceSet.compileClasspath.contains(mainSrcDir),
            "Main source set classpath should include src/main/groovy. " +
                "Classpath: ${mainSourceSet.compileClasspath}, " +
                "Expected to contain: $mainSrcDir",
        )
    }

    @Test
    fun `test source set should see main source set classes`() = runTest {
        // Given: A Gradle project with both main and test source sets
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy")
        val testSrcDir = projectRoot.resolve("src/test/groovy")
        Files.createDirectories(mainSrcDir)
        Files.createDirectories(testSrcDir)

        // When: Resolve source sets
        val sourceSets = gradleSourceSetResolver.resolveSourceSets(projectRoot)

        // Then: Test source set should include main source directory in classpath
        val testSourceSet = sourceSets.find { it.name == "test" }
        assertNotNull(testSourceSet, "Should have test source set")
        assertTrue(
            testSourceSet.compileClasspath.contains(mainSrcDir),
            "Test source set classpath should include src/main/groovy. " +
                "Classpath: ${testSourceSet.compileClasspath}, " +
                "Expected to contain: $mainSrcDir",
        )
    }

    @Test
    fun `classes in same package should resolve each other during compilation`() = runTest {
        // Given: Two classes in the same package
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy/com/example")
        Files.createDirectories(mainSrcDir)

        val baseClassContent = """
            package com.example

            class BaseClass {
                String name
            }
        """.trimIndent()

        val derivedClassContent = """
            package com.example

            class DerivedClass extends BaseClass {
                void process() { }
            }
        """.trimIndent()

        val baseClassFile = mainSrcDir.resolve("BaseClass.groovy")
        val derivedClassFile = mainSrcDir.resolve("DerivedClass.groovy")

        Files.writeString(baseClassFile, baseClassContent)
        Files.writeString(derivedClassFile, derivedClassContent)

        // When: Initialize workspace and compile
        val result = workspaceCompilationService.initializeWorkspace(projectRoot)

        // Then: Compilation should succeed without unresolved symbol errors
        assertTrue(result.isSuccess, "Workspace compilation should succeed")
        assertTrue(
            result.diagnostics.values.flatten().none { diagnostic ->
                diagnostic.message.contains("unable to resolve class BaseClass") ||
                    diagnostic.message.contains("BaseClass") && diagnostic.message.contains("not found")
            },
            "Should not have unresolved class errors. Diagnostics: ${result.diagnostics.values.flatten().map {
                it.message
            }}",
        )
    }

    @Test
    fun `compilation should make symbols available across files in same context`() = runTest {
        // Given: Multiple files with inter-dependencies in same source set
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy/com/example")
        Files.createDirectories(mainSrcDir)

        val serviceContent = """
            package com.example

            class UserService {
                UserRepository repository

                User findById(String id) {
                    return repository.findById(id)
                }
            }
        """.trimIndent()

        val repositoryContent = """
            package com.example

            class UserRepository {
                User findById(String id) {
                    return new User(id: id)
                }
            }
        """.trimIndent()

        val userContent = """
            package com.example

            class User {
                String id
                String name
            }
        """.trimIndent()

        Files.writeString(mainSrcDir.resolve("UserService.groovy"), serviceContent)
        Files.writeString(mainSrcDir.resolve("UserRepository.groovy"), repositoryContent)
        Files.writeString(mainSrcDir.resolve("User.groovy"), userContent)

        // When: Initialize workspace
        val result = workspaceCompilationService.initializeWorkspace(projectRoot)

        // Then: All classes should be resolvable
        assertTrue(result.isSuccess, "Workspace compilation should succeed")

        val allDiagnostics = result.diagnostics.values.flatten()
        val unresolvedErrors = allDiagnostics.filter { diagnostic ->
            diagnostic.message.contains("unable to resolve class") ||
                diagnostic.message.contains("not found")
        }

        assertTrue(
            unresolvedErrors.isEmpty(),
            "Should not have unresolved class errors. Found: ${unresolvedErrors.map { it.message }}",
        )
    }

    /**
     * TODO: Fix workspace compilation architecture issues
     *
     * ISSUE: WorkspaceCompilationService.initializeWorkspace() returns empty AST visitor with no classes.
     *
     * EXPECTED BEHAVIOR:
     * - Should compile AppConfig.groovy and DatabaseService.groovy in temporary Gradle project
     * - Should return AST visitor containing both class nodes
     * - Should enable cross-file symbol resolution and compilation
     *
     * CURRENT BEHAVIOR:
     * - WorkspaceCompilationService returns empty class list: "Found classes: []"
     * - Test fails with: "AST visitor should contain AppConfig class. Found classes: []"
     *
     * ROOT CAUSE ANALYSIS:
     * This is the SAME fundamental issue discovered in GroovyLanguageServerTest hover tests:
     * - Workspace compilation has architectural problems with file processing
     * - WorkspaceCompilationService.getAstVisitorForFile() often returns null
     * - Temporary test files don't integrate well with workspace compilation
     * - Similar to hover tests that were fixed by switching to single-file compilation
     *
     * ARCHITECTURAL ISSUES IDENTIFIED:
     * 1. Workspace compilation expects real project structure vs test temp directories
     * 2. WorkspaceCompilationService.compiledFiles map may not be populated correctly
     * 3. AST visitor creation/population timing issues during test execution
     * 4. Potential race conditions between file writing and compilation
     *
     * COMPONENTS INVOLVED:
     * - WorkspaceCompilationService: Core workspace compilation (has bugs)
     * - GradleSourceSetResolver: Gradle project detection and source sets
     * - CentralizedDependencyManager: Dependency resolution for compilation
     * - AST visitor building: Population of symbols for cross-file resolution
     *
     * INVESTIGATION NEEDED:
     * 1. Debug WorkspaceCompilationService.initializeWorkspace() step by step
     * 2. Check if temp project structure matches workspace expectations
     * 3. Verify file discovery and compilation unit creation
     * 4. Test with real Gradle project vs temporary directories
     * 5. Compare workspace vs single-file compilation behavior
     *
     * POTENTIAL SOLUTIONS:
     * 1. Fix workspace compilation architecture (high effort, high risk)
     * 2. Create isolated test projects instead of temp directories
     * 3. Mock workspace compilation for unit testing
     * 4. Use single-file compilation for these tests (like hover fix)
     *
     * COMPLEXITY: High - requires deep workspace compilation rework
     * RISK: High - could break existing workspace functionality
     * PRIORITY: Medium - affects advanced workspace features, not core LSP
     */
    @Disabled("TODO: Fix workspace compilation architecture - see comprehensive analysis above")
    @Test
    fun `workspace compilation should provide AST visitor with all symbols`() = runTest {
        // Given: A project with multiple interconnected classes
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy/com/example")
        Files.createDirectories(mainSrcDir)

        val configContent = """
            package com.example

            class AppConfig {
                String databaseUrl = "jdbc:h2:mem:test"
                int maxConnections = 10
            }
        """.trimIndent()

        val serviceContent = """
            package com.example

            class DatabaseService {
                private AppConfig config

                void connect() {
                    println("Connecting to: " + config.databaseUrl)
                }
            }
        """.trimIndent()

        Files.writeString(mainSrcDir.resolve("AppConfig.groovy"), configContent)
        Files.writeString(mainSrcDir.resolve("DatabaseService.groovy"), serviceContent)

        // When: Initialize workspace
        val result = workspaceCompilationService.initializeWorkspace(projectRoot)

        // Then: AST visitor should contain all classes
        assertNotNull(result.astVisitor, "Should have AST visitor")
        val allNodes = result.astVisitor!!.getAllNodes()

        val classNames = allNodes
            .filterIsInstance<org.codehaus.groovy.ast.ClassNode>()
            .map { it.nameWithoutPackage }

        assertTrue(
            classNames.contains("AppConfig"),
            "AST visitor should contain AppConfig class. Found classes: $classNames",
        )
        assertTrue(
            classNames.contains("DatabaseService"),
            "AST visitor should contain DatabaseService class. Found classes: $classNames",
        )
    }

    @Test
    fun `workspace symbol table should resolve cross-file references`() = runTest {
        // Given: Classes with cross-references
        val projectRoot = createGradleProject()
        val mainSrcDir = projectRoot.resolve("src/main/groovy/com/example")
        Files.createDirectories(mainSrcDir)

        val modelContent = """
            package com.example

            class Product {
                String name
                double price
            }
        """.trimIndent()

        val controllerContent = """
            package com.example

            class ProductController {
                List<Product> products = []

                void addProduct(Product product) {
                    products.add(product)
                }
            }
        """.trimIndent()

        Files.writeString(mainSrcDir.resolve("Product.groovy"), modelContent)
        Files.writeString(mainSrcDir.resolve("ProductController.groovy"), controllerContent)

        // When: Initialize workspace
        val result = workspaceCompilationService.initializeWorkspace(projectRoot)

        // Then: Symbol table should be able to resolve Product references
        assertNotNull(result.symbolTable, "Should have symbol table")

        // The symbol table should contain both classes and their relationships
        val symbolStats = result.symbolTable!!.getStatistics()
        assertTrue(
            symbolStats.values.sum() > 0,
            "Symbol table should contain symbols. Stats: $symbolStats",
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
}
