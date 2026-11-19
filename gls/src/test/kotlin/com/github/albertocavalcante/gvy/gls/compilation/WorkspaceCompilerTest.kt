// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserRange
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
            semanticIndexer = SemanticIndexer(WorkspaceSymbolIndex(GroovySemanticDB())),
        )
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `deletion during parsing cannot restore semantic records`(): Unit = runTest {
        val file = Files.createDirectories(tempDir.resolve("src")).resolve("Deleted.groovy")
        file.writeText("class Deleted { void greet() {} }")
        workspaceManager.initializeWorkspace(tempDir)
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        val indexer = SemanticIndexer(index)
        val worker = mockk<WorkerSessionManager>()
        coEvery { worker.parse(any()) } coAnswers {
            val result = parser.parse(firstArg())
            Files.delete(file)
            indexer.remove(file.toUri())
            result
        }
        val compiler = WorkspaceCompiler(worker, workspaceManager, indexer)

        compiler.compileWorkspace()

        assertNull(index.getDocument(file.toUri()), "in-flight results must not resurrect deleted records")
    }

    @Test
    fun `cache clear during parsing cannot restore semantic records`(): Unit = runTest {
        val file = Files.createDirectories(tempDir.resolve("src")).resolve("Cleared.groovy")
        file.writeText("class Cleared { void greet() {} }")
        workspaceManager.initializeWorkspace(tempDir)
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        val indexer = SemanticIndexer(index)
        val worker = mockk<WorkerSessionManager>()
        coEvery { worker.parse(any()) } coAnswers {
            val result = parser.parse(firstArg())
            indexer.clear()
            result
        }
        val compiler = WorkspaceCompiler(worker, workspaceManager, indexer)

        compiler.compileWorkspace()

        assertNull(index.getDocument(file.toUri()), "in-flight results must not undo cache invalidation")
    }

    @Test
    fun `compileWorkspace compiles multiple files together`(): Unit = runTest {
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
    fun `compileWorkspace resolves cross-file class references`(): Unit = runTest {
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

        // Verify the resolved module is part of the result
        val derivedModule = result.modules[derivedClass.toUri()]
        assertNotNull(derivedModule, "Should have DerivedClass module")

        // Verify the class structure
        val derivedClassNode = derivedModule.classes.find { it.name == "DerivedClass" }
        assertNotNull(derivedClassNode, "Should find DerivedClass node")
        assertEquals("BaseClass", derivedClassNode.superClass.name, "Should extend BaseClass")
    }

    @Test
    fun `compileWorkspace resolves cross-file method calls`(): Unit = runTest {
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
    fun `compileWorkspace handles syntax errors gracefully`(): Unit = runTest {
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
    fun `workspace reports semantic errors even when an AST was produced`(): Unit = runTest {
        val source = Files.createDirectories(tempDir.resolve("src")).resolve("Invalid.groovy")
        source.writeText("class Invalid {}")
        workspaceManager.initializeWorkspace(tempDir)
        val worker = mockk<WorkerSessionManager>()
        coEvery { worker.parse(any()) } coAnswers {
            parser.parse(firstArg()).copy(
                diagnostics = listOf(ParserDiagnostic(range = ParserRange.point(0, 0), message = "semantic error")),
            )
        }
        val compiler =
            WorkspaceCompiler(worker, workspaceManager, SemanticIndexer(WorkspaceSymbolIndex(GroovySemanticDB())))

        val result = compiler.compileWorkspace()

        assertTrue(source.toUri() in result.modules, "the recovered AST should remain available")
        assertFalse(result.success)
        assertTrue(result.errors.any { it.uri == source.toUri() && "semantic error" in it.message })
    }

    @Test
    fun `compileWorkspace uses SEMANTIC_ANALYSIS phase for full resolution`(): Unit = runTest {
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
    fun `compileWorkspace returns empty result for empty workspace`(): Unit = runTest {
        workspaceManager.initializeWorkspace(tempDir)

        val result = workspaceCompiler.compileWorkspace()

        assertTrue(result.success, "Empty workspace should compile successfully")
        assertTrue(result.modules.isEmpty(), "Should have no modules")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
    }

    @Test
    fun `compileWorkspace handles nested package structure`(): Unit = runTest {
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
}
