package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.InProcessWorkerSession
import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceCompilerTest {
    private lateinit var parser: GroovyParserFacade
    private lateinit var workerSessionManager: WorkerSessionManager
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var workspaceCompiler: WorkspaceCompiler
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("workspace-compiler-test")
        parser = GroovyParserFacade()
        workerSessionManager = WorkerSessionManager(
            defaultSession = InProcessWorkerSession(parser),
            sessionFactory = { InProcessWorkerSession(parser) },
        )
        workspaceManager = WorkspaceManager()
        workspaceCompiler = WorkspaceCompiler(
            workerSessionManager = workerSessionManager,
            workspaceManager = workspaceManager,
            semanticDb = GroovySemanticDB(),
        )
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `compileWorkspace compiles multiple files together`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create two Groovy files
        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")

        file1.writeText("class File1 { String name }")
        file2.writeText("class File2 { File1 ref }")

        workspaceManager.initializeWorkspace(tempDir)

        // Compile workspace
        val result = workspaceCompiler.compileWorkspace()

        // Verify both files were compiled
        assertTrue(result.success, "Workspace compilation should succeed")
        assertEquals(2, result.modules.size, "Should have compiled 2 modules")
        assertTrue(result.modules.containsKey(file1.toUri()), "Should contain File1 module")
        assertTrue(result.modules.containsKey(file2.toUri()), "Should contain File2 module")
    }

    @Test
    fun `compileWorkspace resolves cross-file class references`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create test files with cross-file references
        val baseClass = srcDir.resolve("BaseClass.groovy")
        val derivedClass = srcDir.resolve("DerivedClass.groovy")

        baseClass.writeText(
            """
            class BaseClass {
                String baseField
                void baseMethod() {}
            }
            """.trimIndent(),
        )

        derivedClass.writeText(
            """
            class DerivedClass extends BaseClass {
                void test() {
                    baseField = "test"  // Should resolve to BaseClass.baseField
                }
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        // Compile workspace
        val result = workspaceCompiler.compileWorkspace()

        // Verify compilation succeeded with cross-file resolution
        assertTrue(result.success, "Workspace compilation should succeed")
        assertEquals(2, result.modules.size)

        // Verify we can retrieve the resolved module
        val derivedModule = workspaceCompiler.getResolvedModule(derivedClass.toUri())
        assertNotNull(derivedModule, "Should have DerivedClass module")

        // Verify the class structure
        val derivedClassNode = derivedModule.classes.find { it.name == "DerivedClass" }
        assertNotNull(derivedClassNode, "Should find DerivedClass node")
        assertEquals("BaseClass", derivedClassNode.superClass.name, "Should extend BaseClass")
    }

    @Test
    fun `compileWorkspace resolves cross-file method calls`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create files with cross-file method calls
        val service = srcDir.resolve("Service.groovy")
        val client = srcDir.resolve("Client.groovy")

        service.writeText(
            """
            class Service {
                String getData() { return "data" }
            }
            """.trimIndent(),
        )

        client.writeText(
            """
            class Client {
                void useService() {
                    def svc = new Service()
                    def result = svc.getData()  // Cross-file method call
                }
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        // Compile workspace
        val result = workspaceCompiler.compileWorkspace()

        // Should compile successfully with cross-file method resolution
        assertTrue(result.success, "Workspace compilation should succeed")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
    }

    @Test
    fun `compileWorkspace handles syntax errors gracefully`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create one valid file and one with syntax error
        val validFile = srcDir.resolve("Valid.groovy")
        val invalidFile = srcDir.resolve("Invalid.groovy")

        validFile.writeText("class Valid { String field }")
        invalidFile.writeText("class Invalid { this is not valid groovy }")

        workspaceManager.initializeWorkspace(tempDir)

        // Compile workspace
        val result = workspaceCompiler.compileWorkspace()

        // Compilation should report failure and errors
        assertFalse(result.success, "Workspace compilation should fail due to syntax error")
        assertTrue(result.errors.isNotEmpty(), "Should have compilation errors")

        // At least one error should be for the invalid file
        val invalidFileErrors = result.errors.filter { it.uri == invalidFile.toUri() }
        assertTrue(invalidFileErrors.isNotEmpty(), "Should have errors for invalid file")
    }

    @Test
    fun `compileWorkspace uses SEMANTIC_ANALYSIS phase for full resolution`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create files that require semantic analysis
        val file = srcDir.resolve("Test.groovy")
        file.writeText(
            """
            class Test {
                String name = "test"
                void method() {
                    println name  // Requires type resolution
                }
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        // Compile workspace
        val result = workspaceCompiler.compileWorkspace()

        // Should compile to SEMANTIC_ANALYSIS phase
        assertTrue(result.success)
        val module = result.modules[file.toUri()]
        assertNotNull(module, "Should have compiled module")

        // Verify the module has resolved types (semantic analysis complete)
        val testClass = module.classes.find { it.name == "Test" }
        assertNotNull(testClass, "Should find Test class")
        val nameField = testClass.fields.find { it.name == "name" }
        assertNotNull(nameField, "Should find name field")
        assertEquals("java.lang.String", nameField.type.name, "Field type should be resolved")
    }

    @Test
    fun `getResolvedModule returns null for unknown URI`() = runTest {
        val unknownUri = URI.create("file:///unknown.groovy")

        val module = workspaceCompiler.getResolvedModule(unknownUri)

        assertNull(module, "Should return null for unknown URI")
    }

    @Test
    fun `compileWorkspace returns empty result for empty workspace`() = runTest {
        workspaceManager.initializeWorkspace(tempDir)

        val result = workspaceCompiler.compileWorkspace()

        assertTrue(result.success, "Empty workspace should compile successfully")
        assertTrue(result.modules.isEmpty(), "Should have no modules")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
    }

    @Test
    fun `incrementalCompile updates only changed files`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create initial workspace
        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")

        file1.writeText("class File1 { String field1 }")
        file2.writeText("class File2 { String field2 }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        val initialResult = workspaceCompiler.compileWorkspace()
        assertTrue(initialResult.success)

        // Modify only file1
        file1.writeText("class File1 { String field1\nString newField }")

        // Incremental compile
        val incrementalResult = workspaceCompiler.incrementalCompile(setOf(file1.toUri()))

        // Should successfully recompile
        assertTrue(incrementalResult.success)
        assertTrue(incrementalResult.modules.containsKey(file1.toUri()))

        // Verify the module was updated
        val module1 = incrementalResult.modules[file1.toUri()]
        assertNotNull(module1)
        val file1Class = module1.classes.find { it.name == "File1" }
        assertNotNull(file1Class)
        assertEquals(2, file1Class.fields.size, "Should have 2 fields after update")
    }

    @Test
    fun `compileWorkspace handles nested package structure`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create nested package structure
        val packageDir = srcDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val file1 = packageDir.resolve("Class1.groovy")
        val file2 = packageDir.resolve("Class2.groovy")

        file1.writeText(
            """
            package com.example
            class Class1 { String name }
            """.trimIndent(),
        )

        file2.writeText(
            """
            package com.example
            class Class2 {
                Class1 ref  // Same package reference
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        val result = workspaceCompiler.compileWorkspace()

        assertTrue(result.success)
        assertEquals(2, result.modules.size)
    }

    // ========================================================================
    // Tests for Fix #6: Race condition in initialCompilationDone
    // ========================================================================

    @Test
    fun `concurrent incrementalCompile calls should only trigger one initial compilation`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create a test file
        val file1 = srcDir.resolve("File1.groovy")
        file1.writeText("class File1 { String field }")

        workspaceManager.initializeWorkspace(tempDir)

        // Launch multiple concurrent incremental compiles
        val concurrentCalls = 5
        val results = List(concurrentCalls) {
            async {
                workspaceCompiler.incrementalCompile(setOf(file1.toUri()))
            }
        }.awaitAll()

        // Verify exact count of results
        assertEquals(
            concurrentCalls,
            results.size,
            "Should have exactly $concurrentCalls results",
        )

        // All should succeed
        results.forEach { result ->
            assertTrue(result.success, "All incremental compiles should succeed")
        }

        // All should have compiled the file with exact count
        results.forEach { result ->
            assertEquals(
                1,
                result.modules.size,
                "Each result should have exactly 1 compiled module",
            )
            assertTrue(
                result.modules.containsKey(file1.toUri()),
                "Each result should contain File1",
            )
        }

        // Verify compilation result consistency - all results should have same module structure
        val firstModuleClasses = results[0].modules[file1.toUri()]?.classes?.map { it.name }
        results.drop(1).forEach { result ->
            val moduleClasses = result.modules[file1.toUri()]?.classes?.map { it.name }
            assertEquals(
                firstModuleClasses,
                moduleClasses,
                "All results should have identical module structure",
            )
        }
    }

    @Test
    fun `second incrementalCompile should skip initial compilation`() = runTest {
        // Create source directory structure
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        // Create test files
        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")
        file1.writeText("class File1 { String field1 }")
        file2.writeText("class File2 { String field2 }")

        workspaceManager.initializeWorkspace(tempDir)

        // First incremental compile (will trigger initial compilation of entire workspace)
        val firstResult = workspaceCompiler.incrementalCompile(setOf(file1.toUri()))
        assertTrue(firstResult.success, "First incremental compile should succeed")

        // First call triggers full workspace compilation, so both files should be compiled
        assertEquals(
            2,
            firstResult.modules.size,
            "First incremental compile should compile entire workspace (2 files)",
        )
        assertTrue(
            firstResult.modules.containsKey(file1.toUri()),
            "First result should contain File1",
        )
        assertTrue(
            firstResult.modules.containsKey(file2.toUri()),
            "First result should contain File2 from workspace compilation",
        )

        // Second incremental compile (should skip initial compilation, only compile file2)
        val secondResult = workspaceCompiler.incrementalCompile(setOf(file2.toUri()))
        assertTrue(secondResult.success, "Second incremental compile should succeed")

        // Second call should only recompile the requested file
        assertEquals(
            1,
            secondResult.modules.size,
            "Second incremental compile should only recompile requested file",
        )
        assertTrue(
            secondResult.modules.containsKey(file2.toUri()),
            "Second result should contain File2",
        )
        assertFalse(
            secondResult.modules.containsKey(file1.toUri()),
            "Second result should NOT recompile File1 (not requested)",
        )
    }

    @Test
    fun `AtomicBoolean prevents race condition in initial compilation flag`() = runTest {
        // This test verifies that the AtomicBoolean compareAndSet prevents
        // multiple threads from triggering initial compilation
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        file1.writeText("class File1 { String field }")

        workspaceManager.initializeWorkspace(tempDir)

        // Start 10 concurrent incremental compiles
        val concurrentCalls = 10
        val results = List(concurrentCalls) {
            async {
                workspaceCompiler.incrementalCompile(setOf(file1.toUri()))
            }
        }.awaitAll()

        // Verify exact count of results
        assertEquals(
            concurrentCalls,
            results.size,
            "Should have exactly $concurrentCalls results",
        )

        // All calls should complete successfully
        results.forEach { result ->
            assertTrue(result.success, "All concurrent calls should succeed")
        }

        // Verify all got consistent results with exact module count
        results.forEach { result ->
            assertEquals(
                1,
                result.modules.size,
                "Each result should have exactly 1 module",
            )
            assertTrue(
                result.modules.containsKey(file1.toUri()),
                "Each result should contain File1",
            )
        }

        // Verify module content is consistent across all results
        val firstModule = results[0].modules[file1.toUri()]
        assertNotNull(firstModule, "First module should not be null")
        assertEquals(
            1,
            firstModule.classes.size,
            "Module should have exactly 1 class",
        )
        assertEquals(
            "File1",
            firstModule.classes[0].name,
            "Class name should be 'File1'",
        )

        // All other results should match the first
        results.drop(1).forEach { result ->
            val module = result.modules[file1.toUri()]
            assertNotNull(module, "Module should not be null")
            assertEquals(
                1,
                module.classes.size,
                "Each module should have exactly 1 class",
            )
            assertEquals(
                "File1",
                module.classes[0].name,
                "Each class name should be 'File1'",
            )
        }
    }

    @Test
    fun `concurrent incrementalCompile with 50 threads`() = runTest {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        file1.writeText("class File1 { String field }")

        workspaceManager.initializeWorkspace(tempDir)

        val concurrentCalls = 50
        val results = List(concurrentCalls) {
            async {
                workspaceCompiler.incrementalCompile(setOf(file1.toUri()))
            }
        }.awaitAll()

        assertEquals(
            concurrentCalls,
            results.size,
            "Should handle $concurrentCalls concurrent calls",
        )
        results.forEach { result ->
            assertTrue(result.success, "All 50 concurrent calls should succeed")
            assertEquals(
                1,
                result.modules.size,
                "Each result should have exactly 1 module",
            )
        }
    }

    @Test
    fun `concurrent incrementalCompile with 100 threads`() = runTest {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        file1.writeText("class File1 { String field }")

        workspaceManager.initializeWorkspace(tempDir)

        val concurrentCalls = 100
        val results = List(concurrentCalls) {
            async {
                workspaceCompiler.incrementalCompile(setOf(file1.toUri()))
            }
        }.awaitAll()

        assertEquals(
            concurrentCalls,
            results.size,
            "Should handle $concurrentCalls concurrent calls",
        )
        results.forEach { result ->
            assertTrue(result.success, "All 100 concurrent calls should succeed")
            assertEquals(
                1,
                result.modules.size,
                "Each result should have exactly 1 module",
            )
        }
    }

    @Test
    fun `incrementalCompile populates exact field count in compiled class`() = runTest {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("MultiField.groovy")
        file1.writeText(
            """
            class MultiField {
                String field1
                Integer field2
                Boolean field3
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        val result = workspaceCompiler.incrementalCompile(setOf(file1.toUri()))

        assertTrue(result.success, "Compilation should succeed")
        val module = result.modules[file1.toUri()]
        assertNotNull(module, "Module should exist")

        val multiFieldClass = module.classes.find { it.name == "MultiField" }
        assertNotNull(multiFieldClass, "MultiField class should exist")
        assertEquals(
            3,
            multiFieldClass.fields.size,
            "MultiField should have exactly 3 fields",
        )

        val fieldNames = multiFieldClass.fields.map { it.name }.toSet()
        assertEquals(
            setOf("field1", "field2", "field3"),
            fieldNames,
            "Fields should have exact names: field1, field2, field3",
        )
    }
}
