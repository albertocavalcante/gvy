package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.InProcessWorkerSession
import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IncrementalCompilerTest {
    private lateinit var parser: GroovyParserFacade
    private lateinit var workerSessionManager: WorkerSessionManager
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var workspaceCompiler: WorkspaceCompiler
    private lateinit var semanticDb: GroovySemanticDB
    private lateinit var dependencyGraph: DependencyGraph
    private lateinit var incrementalCompiler: IncrementalCompiler
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("incremental-compiler-test")
        parser = GroovyParserFacade()
        workerSessionManager = WorkerSessionManager(
            defaultSession = InProcessWorkerSession(parser),
            sessionFactory = { InProcessWorkerSession(parser) },
        )
        workspaceManager = WorkspaceManager()
        semanticDb = GroovySemanticDB()
        workspaceCompiler = WorkspaceCompiler(
            workerSessionManager = workerSessionManager,
            workspaceManager = workspaceManager,
            semanticDb = semanticDb,
        )
        dependencyGraph = DependencyGraph()
        incrementalCompiler = IncrementalCompiler(
            workspaceCompiler = workspaceCompiler,
            dependencyGraph = dependencyGraph,
            semanticDb = semanticDb,
        )
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `initialCompile compiles all workspace files`() = runTest {
        // Create source files
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")

        file1.writeText("class File1 { String field1 }")
        file2.writeText("class File2 { String field2 }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        val result = incrementalCompiler.initialCompile()

        assertTrue(result.success, "Initial compilation should succeed")
        assertEquals(2, result.modules.size, "Should compile 2 modules")
        assertTrue(result.modules.containsKey(file1.toUri()))
        assertTrue(result.modules.containsKey(file2.toUri()))
    }

    @Test
    fun `compile with single changed file only recompiles that file`() = runTest {
        // Create source files
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")

        file1.writeText("class File1 { String field1 }")
        file2.writeText("class File2 { String field2 }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Modify file1
        file1.writeText("class File1 { String field1\nString newField }")

        // Incremental compile only file1
        val result = incrementalCompiler.compile(setOf(file1.toUri()))

        assertTrue(result.success, "Incremental compilation should succeed")
        assertTrue(result.recompiledFiles.contains(file1.toUri()), "Should recompile file1")
        assertFalse(result.recompiledFiles.contains(file2.toUri()), "Should NOT recompile file2")
    }

    @Test
    fun `compile with changed file recompiles dependents`() = runTest {
        // Create source files with dependency
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val base = srcDir.resolve("Base.groovy")
        val derived = srcDir.resolve("Derived.groovy")

        base.writeText("class Base { String baseField }")
        derived.writeText("class Derived extends Base { String derivedField }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation (this builds dependency graph)
        incrementalCompiler.initialCompile()

        // Modify base class
        base.writeText("class Base { String baseField\nString newField }")

        // Incremental compile - should recompile both Base and Derived
        val result = incrementalCompiler.compile(setOf(base.toUri()))

        assertTrue(result.success, "Incremental compilation should succeed")
        assertTrue(result.recompiledFiles.contains(base.toUri()), "Should recompile Base")
        assertTrue(result.recompiledFiles.contains(derived.toUri()), "Should recompile Derived (dependent)")
        assertEquals(2, result.recompiledFiles.size, "Should recompile exactly 2 files")
    }

    @Test
    fun `compile with multiple changed files recompiles all affected`() = runTest {
        // Create complex dependency tree
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val fileA = srcDir.resolve("FileA.groovy")
        val fileB = srcDir.resolve("FileB.groovy")
        val fileC = srcDir.resolve("FileC.groovy")
        val fileD = srcDir.resolve("FileD.groovy")

        fileA.writeText("class FileA { }")
        fileB.writeText("class FileB extends FileA { }")
        fileC.writeText("class FileC extends FileA { }")
        fileD.writeText("class FileD { }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Modify FileA and FileD
        fileA.writeText("class FileA { String newField }")
        fileD.writeText("class FileD { String field }")

        // Incremental compile
        val result = incrementalCompiler.compile(setOf(fileA.toUri(), fileD.toUri()))

        assertTrue(result.success)
        // Should recompile A, B, C (dependents of A), and D
        assertEquals(4, result.recompiledFiles.size, "Should recompile A, B, C, D")
        assertTrue(
            result.recompiledFiles.containsAll(setOf(fileA.toUri(), fileB.toUri(), fileC.toUri(), fileD.toUri())),
        )
    }

    @Test
    fun `compile handles syntax errors in changed file`() = runTest {
        // Create source files
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file1 = srcDir.resolve("File1.groovy")
        val file2 = srcDir.resolve("File2.groovy")

        file1.writeText("class File1 { String field1 }")
        file2.writeText("class File2 { String field2 }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Introduce syntax error in file1
        file1.writeText("class File1 { this is invalid }")

        // Incremental compile
        val result = incrementalCompiler.compile(setOf(file1.toUri()))

        assertFalse(result.success, "Compilation should fail due to syntax error")
        assertTrue(result.errors.isNotEmpty(), "Should have compilation errors")
        assertTrue(result.errors.any { it.uri == file1.toUri() }, "Error should be for file1")
    }

    @Test
    fun `compile with transitive dependencies`() = runTest {
        // Create chain: Top -> Middle -> Base
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val base = srcDir.resolve("Base.groovy")
        val middle = srcDir.resolve("Middle.groovy")
        val top = srcDir.resolve("Top.groovy")

        base.writeText("class Base { String baseField }")
        middle.writeText("class Middle extends Base { String middleField }")
        top.writeText("class Top extends Middle { String topField }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Modify base - should trigger recompilation of all 3
        base.writeText("class Base { String baseField\nString newField }")

        val result = incrementalCompiler.compile(setOf(base.toUri()))

        assertTrue(result.success)
        assertEquals(3, result.recompiledFiles.size, "Should recompile all 3 files")
        assertTrue(result.recompiledFiles.containsAll(setOf(base.toUri(), middle.toUri(), top.toUri())))
    }

    @Test
    fun `compile with import dependencies`() = runTest {
        // Create files with import relationships
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

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
                }
            }
            """.trimIndent(),
        )

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Modify Service
        service.writeText(
            """
            class Service {
                String getData() { return "updated" }
            }
            """.trimIndent(),
        )

        val result = incrementalCompiler.compile(setOf(service.toUri()))

        assertTrue(result.success, "Compilation should succeed")
        // Currently does full recompilation, so both files should be in the result
        assertTrue(result.recompiledFiles.contains(service.toUri()), "Should include Service in affected files")
    }

    @Test
    fun `compile updates dependency graph`() = runTest {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val base = srcDir.resolve("Base.groovy")
        val derived = srcDir.resolve("Derived.groovy")

        base.writeText("class Base { String field }")
        derived.writeText("class Derived extends Base { }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation - should build dependency graph
        incrementalCompiler.initialCompile()

        // Verify dependency graph was built
        val stats = dependencyGraph.getStatistics()
        assertTrue(stats.totalFiles > 0, "Dependency graph should track files")
    }

    @Test
    fun `compile with no changes performs no recompilation`() = runTest {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val file = srcDir.resolve("Test.groovy")
        file.writeText("class Test { String field }")

        workspaceManager.initializeWorkspace(tempDir)

        // Initial compilation
        incrementalCompiler.initialCompile()

        // Compile with empty change set
        val result = incrementalCompiler.compile(emptySet())

        assertTrue(result.success)
        assertTrue(result.recompiledFiles.isEmpty(), "Should not recompile any files")
    }
}
