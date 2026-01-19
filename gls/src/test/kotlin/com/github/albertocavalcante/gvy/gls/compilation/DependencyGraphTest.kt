package com.github.albertocavalcante.gvy.gls.compilation

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyGraphTest {

    @Test
    fun `addDependency adds single dependency`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")

        graph.addDependency(fileA, fileB)

        val dependencies = graph.getDependencies(fileA)
        assertTrue(dependencies.contains(fileB), "FileA should depend on FileB")
        assertEquals(1, dependencies.size, "FileA should have exactly 1 dependency")
    }

    @Test
    fun `addDependency tracks reverse dependencies (dependents)`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")

        graph.addDependency(fileA, fileB)

        val dependents = graph.getDependents(fileB)
        assertTrue(dependents.contains(fileA), "FileB should have FileA as a dependent")
        assertEquals(1, dependents.size, "FileB should have exactly 1 dependent")
    }

    @Test
    fun `addDependency handles multiple dependencies from one file`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        graph.addDependency(fileA, fileB)
        graph.addDependency(fileA, fileC)

        val dependencies = graph.getDependencies(fileA)
        assertEquals(2, dependencies.size, "FileA should have 2 dependencies")
        assertTrue(dependencies.contains(fileB))
        assertTrue(dependencies.contains(fileC))
    }

    @Test
    fun `addDependency handles multiple dependents of one file`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        graph.addDependency(fileA, fileC)
        graph.addDependency(fileB, fileC)

        val dependents = graph.getDependents(fileC)
        assertEquals(2, dependents.size, "FileC should have 2 dependents")
        assertTrue(dependents.contains(fileA))
        assertTrue(dependents.contains(fileB))
    }

    @Test
    fun `getDependents returns transitive dependents`() {
        val graph = DependencyGraph()
        val base = URI.create("file:///Base.groovy")
        val middle = URI.create("file:///Middle.groovy")
        val top = URI.create("file:///Top.groovy")

        // Chain: Top -> Middle -> Base
        graph.addDependency(middle, base)
        graph.addDependency(top, middle)

        val dependents = graph.getDependents(base)
        assertEquals(2, dependents.size, "Base should have 2 transitive dependents")
        assertTrue(dependents.contains(middle), "Should include direct dependent Middle")
        assertTrue(dependents.contains(top), "Should include transitive dependent Top")
    }

    @Test
    fun `getDependents handles circular dependencies gracefully`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        // Create cycle: A -> B -> C -> A
        graph.addDependency(fileA, fileB)
        graph.addDependency(fileB, fileC)
        graph.addDependency(fileC, fileA)

        // Should not infinite loop
        val dependents = graph.getDependents(fileA)
        assertTrue(dependents.size <= 3, "Should handle cycle without infinite loop")
    }

    @Test
    fun `removeFile clears all dependencies and dependents`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        graph.addDependency(fileA, fileB)
        graph.addDependency(fileB, fileC)

        graph.removeFile(fileB)

        val depsA = graph.getDependencies(fileA)
        assertFalse(depsA.contains(fileB), "FileA should no longer depend on removed FileB")

        val depsB = graph.getDependencies(fileB)
        assertTrue(depsB.isEmpty(), "Removed file should have no dependencies")

        val dependentsC = graph.getDependents(fileC)
        assertFalse(dependentsC.contains(fileB), "FileC should no longer have removed FileB as dependent")
    }

    @Test
    fun `getAffectedFiles returns changed files plus their dependents`() {
        val graph = DependencyGraph()
        val base = URI.create("file:///Base.groovy")
        val middle = URI.create("file:///Middle.groovy")
        val top = URI.create("file:///Top.groovy")
        val independent = URI.create("file:///Independent.groovy")

        // Chain: Top -> Middle -> Base
        graph.addDependency(middle, base)
        graph.addDependency(top, middle)

        val affected = graph.getAffectedFiles(setOf(base))

        assertEquals(3, affected.size, "Should include changed file and all transitive dependents")
        assertTrue(affected.contains(base), "Should include changed file itself")
        assertTrue(affected.contains(middle), "Should include direct dependent")
        assertTrue(affected.contains(top), "Should include transitive dependent")
        assertFalse(affected.contains(independent), "Should not include independent file")
    }

    @Test
    fun `getAffectedFiles handles multiple changed files`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")
        val fileD = URI.create("file:///FileD.groovy")

        graph.addDependency(fileC, fileA)
        graph.addDependency(fileD, fileB)

        val affected = graph.getAffectedFiles(setOf(fileA, fileB))

        assertEquals(4, affected.size)
        assertTrue(affected.contains(fileA))
        assertTrue(affected.contains(fileB))
        assertTrue(affected.contains(fileC))
        assertTrue(affected.contains(fileD))
    }

    @Test
    fun `updateFromModule extracts dependencies from imports`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///src/FileA.groovy")
        val fileB = URI.create("file:///src/FileB.groovy")

        // Create a workspace index that can resolve class names to URIs
        val workspaceIndex = mapOf("FileB" to fileB)

        // Create a module with an import
        val moduleNode = ModuleNode(null as SourceUnit?)
        val classNode = ClassNode("FileB", 0, null, null, null)
        moduleNode.addImport("FileB", classNode)

        graph.updateFromModule(fileA, moduleNode, workspaceIndex)

        val dependencies = graph.getDependencies(fileA)
        assertTrue(dependencies.contains(fileB), "Should extract dependency from import")
    }

    @Test
    fun `updateFromModule extracts dependencies from superclass`() {
        val graph = DependencyGraph()
        val derived = URI.create("file:///src/Derived.groovy")
        val base = URI.create("file:///src/Base.groovy")

        val workspaceIndex = mapOf("Base" to base)

        val moduleNode = ModuleNode(null as SourceUnit?)
        val classNode = ClassNode("Derived", 0, null, null, null)
        classNode.superClass = ClassNode("Base", 0, null, null, null)
        moduleNode.classes.add(classNode)

        graph.updateFromModule(derived, moduleNode, workspaceIndex)

        val dependencies = graph.getDependencies(derived)
        assertTrue(dependencies.contains(base), "Should extract dependency from superclass")
    }

    @Test
    fun `updateFromModule extracts dependencies from interfaces`() {
        val graph = DependencyGraph()
        val impl = URI.create("file:///src/Implementation.groovy")
        val iface = URI.create("file:///src/Interface.groovy")

        val workspaceIndex = mapOf("Interface" to iface)

        val moduleNode = ModuleNode(null as SourceUnit?)
        val classNode = ClassNode("Implementation", 0, null, null, null)
        classNode.interfaces = arrayOf(ClassNode("Interface", 0, null, null, null))
        moduleNode.classes.add(classNode)

        graph.updateFromModule(impl, moduleNode, workspaceIndex)

        val dependencies = graph.getDependencies(impl)
        assertTrue(dependencies.contains(iface), "Should extract dependency from interface")
    }

    @Test
    fun `updateFromModule replaces old dependencies with new ones`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        val workspaceIndex = mapOf(
            "FileB" to fileB,
            "FileC" to fileC,
        )

        // First update: A depends on B
        val moduleNode1 = ModuleNode(null as SourceUnit?)
        val classNode1 = ClassNode("FileB", 0, null, null, null)
        moduleNode1.addImport("FileB", classNode1)
        graph.updateFromModule(fileA, moduleNode1, workspaceIndex)

        // Second update: A depends on C (not B)
        val moduleNode2 = ModuleNode(null as SourceUnit?)
        val classNode2 = ClassNode("FileC", 0, null, null, null)
        moduleNode2.addImport("FileC", classNode2)
        graph.updateFromModule(fileA, moduleNode2, workspaceIndex)

        val dependencies = graph.getDependencies(fileA)
        assertEquals(1, dependencies.size, "Should have exactly 1 dependency after update")
        assertTrue(dependencies.contains(fileC), "Should depend on FileC")
        assertFalse(dependencies.contains(fileB), "Should no longer depend on FileB")
    }

    @Test
    fun `getDependencies returns empty set for file with no dependencies`() {
        val graph = DependencyGraph()
        val file = URI.create("file:///File.groovy")

        val dependencies = graph.getDependencies(file)

        assertTrue(dependencies.isEmpty(), "File with no dependencies should return empty set")
    }

    @Test
    fun `getDependents returns empty set for file with no dependents`() {
        val graph = DependencyGraph()
        val file = URI.create("file:///File.groovy")

        val dependents = graph.getDependents(file)

        assertTrue(dependents.isEmpty(), "File with no dependents should return empty set")
    }

    @Test
    fun `addDependency is idempotent`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")

        graph.addDependency(fileA, fileB)
        graph.addDependency(fileA, fileB) // Add again

        val dependencies = graph.getDependencies(fileA)
        assertEquals(1, dependencies.size, "Should not add duplicate dependency")
    }

    @Test
    fun `graph handles complex dependency tree`() {
        val graph = DependencyGraph()

        // Create a complex dependency tree:
        //       A
        //      / \
        //     B   C
        //     |   |
        //     D   E
        //      \ /
        //       F

        val a = URI.create("file:///A.groovy")
        val b = URI.create("file:///B.groovy")
        val c = URI.create("file:///C.groovy")
        val d = URI.create("file:///D.groovy")
        val e = URI.create("file:///E.groovy")
        val f = URI.create("file:///F.groovy")

        graph.addDependency(a, b)
        graph.addDependency(a, c)
        graph.addDependency(b, d)
        graph.addDependency(c, e)
        graph.addDependency(d, f)
        graph.addDependency(e, f)

        // Test affected files when F changes
        val affected = graph.getAffectedFiles(setOf(f))

        assertEquals(6, affected.size, "All files should be affected when F changes")
        assertTrue(affected.containsAll(setOf(a, b, c, d, e, f)))
    }

    // Tests for getCompilationSources (Issue #743 - bounded workspace selection)

    @Test
    fun `getCompilationSources returns direct dependencies and dependents`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        // A depends on B, C depends on A
        // So A's compilation sources should be: B (dependency) + C (dependent)
        graph.addDependency(fileA, fileB)
        graph.addDependency(fileC, fileA)

        val compilationSources = graph.getCompilationSources(fileA)

        assertEquals(2, compilationSources.size)
        assertTrue(compilationSources.contains(fileB), "Should include dependency B")
        assertTrue(compilationSources.contains(fileC), "Should include dependent C")
        assertFalse(compilationSources.contains(fileA), "Should not include the file itself")
    }

    @Test
    fun `getCompilationSources returns empty set for file with no dependencies`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")

        val compilationSources = graph.getCompilationSources(fileA)

        assertTrue(compilationSources.isEmpty())
    }

    @Test
    fun `getCompilationSources only returns direct dependents not transitive`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")
        val fileC = URI.create("file:///FileC.groovy")

        // B depends on A, C depends on B (transitive dependency on A)
        graph.addDependency(fileB, fileA)
        graph.addDependency(fileC, fileB)

        val compilationSources = graph.getCompilationSources(fileA)

        assertEquals(1, compilationSources.size)
        assertTrue(compilationSources.contains(fileB), "Should include direct dependent B")
        assertFalse(compilationSources.contains(fileC), "Should NOT include transitive dependent C")
    }

    @Test
    fun `hasInfo returns true for file with dependencies`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")

        graph.addDependency(fileA, fileB)

        assertTrue(graph.hasInfo(fileA), "File with dependencies should have info")
    }

    @Test
    fun `hasInfo returns true for file with dependents`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")
        val fileB = URI.create("file:///FileB.groovy")

        graph.addDependency(fileA, fileB)

        assertTrue(graph.hasInfo(fileB), "File with dependents should have info")
    }

    @Test
    fun `hasInfo returns false for unknown file`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///FileA.groovy")

        assertFalse(graph.hasInfo(fileA), "Unknown file should have no info")
    }

    // Determinism regression tests (PR #930)

    @Test
    fun `processStarImport produces deterministic output for multiple classes`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///src/FileA.groovy")

        // Create workspace index with multiple classes in the same package
        // Using names that would have different iteration order in HashMap vs sorted
        val workspaceIndex = mapOf(
            "com.example.Zebra" to URI.create("file:///src/com/example/Zebra.groovy"),
            "com.example.Alpha" to URI.create("file:///src/com/example/Alpha.groovy"),
            "com.example.Middle" to URI.create("file:///src/com/example/Middle.groovy"),
            "com.example.Beta" to URI.create("file:///src/com/example/Beta.groovy"),
        )

        // Create a module with a star import
        val moduleNode = ModuleNode(null as SourceUnit?)
        // Note: We need to manually create the ImportNode because addStarImport(String)
        // doesn't set type/className which the current DependencyGraph code expects
        val packageClassNode = ClassNode("com.example", 0, null, null, null)
        val starImport = ImportNode(packageClassNode, null)
        moduleNode.starImports.add(starImport)

        // Update and verify dependencies are extracted
        graph.updateFromModule(fileA, moduleNode, workspaceIndex)
        val dependencies = graph.getDependencies(fileA)

        // Verify the expected classes are included
        assertEquals(4, dependencies.size, "Should have dependencies on all 4 classes in package")

        // Execute multiple times and collect the results to verify determinism
        val results = mutableListOf<List<URI>>()
        repeat(10) {
            val testGraph = DependencyGraph()
            testGraph.updateFromModule(fileA, moduleNode, workspaceIndex)
            results.add(testGraph.getDependencies(fileA).sorted())
        }

        // All results should be identical (deterministic)
        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(firstResult, result, "Star import processing should produce deterministic results")
        }

        // Verify the dependencies are in sorted order
        val actualDeps = dependencies.sorted()
        val expectedUris = listOf(
            URI.create("file:///src/com/example/Alpha.groovy"),
            URI.create("file:///src/com/example/Beta.groovy"),
            URI.create("file:///src/com/example/Middle.groovy"),
            URI.create("file:///src/com/example/Zebra.groovy"),
        )

        assertEquals(expectedUris, actualDeps, "Dependencies should be extracted in deterministic sorted order")
    }

    @Test
    fun `static import processing is deterministic with multiple imports`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///src/FileA.groovy")

        // Create workspace index with classes that would have different HashMap iteration order
        val workspaceIndex = mapOf(
            "Zebra" to URI.create("file:///src/Zebra.groovy"),
            "Alpha" to URI.create("file:///src/Alpha.groovy"),
            "Middle" to URI.create("file:///src/Middle.groovy"),
            "Beta" to URI.create("file:///src/Beta.groovy"),
        )

        // Create a module with multiple static imports
        val moduleNode = ModuleNode(null as SourceUnit?)

        // Add static imports in non-alphabetical order
        moduleNode.addStaticImport(ClassNode("Zebra", 0, null, null, null), "zebraMethod", "zebraMethod")
        moduleNode.addStaticImport(ClassNode("Alpha", 0, null, null, null), "alphaMethod", "alphaMethod")
        moduleNode.addStaticImport(ClassNode("Middle", 0, null, null, null), "middleMethod", "middleMethod")
        moduleNode.addStaticImport(ClassNode("Beta", 0, null, null, null), "betaMethod", "betaMethod")

        // Execute multiple times and collect the results
        val results = mutableListOf<List<URI>>()
        repeat(10) {
            val testGraph = DependencyGraph()
            testGraph.updateFromModule(fileA, moduleNode, workspaceIndex)
            results.add(testGraph.getDependencies(fileA).sorted())
        }

        // All results should be identical (deterministic)
        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(firstResult, result, "Static import processing should produce deterministic results")
        }

        // Verify all classes are included
        graph.updateFromModule(fileA, moduleNode, workspaceIndex)
        val dependencies = graph.getDependencies(fileA)
        assertEquals(4, dependencies.size, "Should have dependencies on all 4 statically imported classes")
        assertTrue(dependencies.contains(URI.create("file:///src/Alpha.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Beta.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Middle.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Zebra.groovy")))
    }

    @Test
    fun `static star import processing is deterministic with multiple imports`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///src/FileA.groovy")

        // Create workspace index with classes that would have different HashMap iteration order
        val workspaceIndex = mapOf(
            "Zebra" to URI.create("file:///src/Zebra.groovy"),
            "Alpha" to URI.create("file:///src/Alpha.groovy"),
            "Middle" to URI.create("file:///src/Middle.groovy"),
            "Beta" to URI.create("file:///src/Beta.groovy"),
        )

        // Create a module with multiple static star imports
        val moduleNode = ModuleNode(null as SourceUnit?)

        // Add static star imports in non-alphabetical order
        moduleNode.addStaticStarImport("Zebra", ClassNode("Zebra", 0, null, null, null))
        moduleNode.addStaticStarImport("Alpha", ClassNode("Alpha", 0, null, null, null))
        moduleNode.addStaticStarImport("Middle", ClassNode("Middle", 0, null, null, null))
        moduleNode.addStaticStarImport("Beta", ClassNode("Beta", 0, null, null, null))

        // Execute multiple times and collect the results
        val results = mutableListOf<List<URI>>()
        repeat(10) {
            val testGraph = DependencyGraph()
            testGraph.updateFromModule(fileA, moduleNode, workspaceIndex)
            results.add(testGraph.getDependencies(fileA).sorted())
        }

        // All results should be identical (deterministic)
        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(firstResult, result, "Static star import processing should produce deterministic results")
        }

        // Verify all classes are included
        graph.updateFromModule(fileA, moduleNode, workspaceIndex)
        val dependencies = graph.getDependencies(fileA)
        assertEquals(4, dependencies.size, "Should have dependencies on all 4 static star imported classes")
        assertTrue(dependencies.contains(URI.create("file:///src/Alpha.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Beta.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Middle.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/Zebra.groovy")))
    }

    @Test
    fun `combined import processing maintains determinism across all import types`() {
        val graph = DependencyGraph()
        val fileA = URI.create("file:///src/FileA.groovy")

        val workspaceIndex = mapOf(
            // Regular imports
            "RegularZ" to URI.create("file:///src/RegularZ.groovy"),
            "RegularA" to URI.create("file:///src/RegularA.groovy"),
            // Star import package
            "pkg.ClassZ" to URI.create("file:///src/pkg/ClassZ.groovy"),
            "pkg.ClassA" to URI.create("file:///src/pkg/ClassA.groovy"),
            // Static imports
            "StaticZ" to URI.create("file:///src/StaticZ.groovy"),
            "StaticA" to URI.create("file:///src/StaticA.groovy"),
        )

        val moduleNode = ModuleNode(null as SourceUnit?)

        // Mix all types of imports - testing both regular and static imports
        // (star imports are tested in a separate test)
        moduleNode.addImport("RegularZ", ClassNode("RegularZ", 0, null, null, null))
        moduleNode.addImport("RegularA", ClassNode("RegularA", 0, null, null, null))
        moduleNode.addStaticImport(ClassNode("StaticZ", 0, null, null, null), "method", "methodZ")
        moduleNode.addStaticImport(ClassNode("StaticA", 0, null, null, null), "method", "methodA")

        // Execute multiple times and verify determinism
        val results = mutableListOf<List<URI>>()
        repeat(10) {
            val testGraph = DependencyGraph()
            testGraph.updateFromModule(fileA, moduleNode, workspaceIndex)
            results.add(testGraph.getDependencies(fileA).sorted())
        }

        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(firstResult, result, "Combined import processing should be deterministic")
        }

        // Verify all expected dependencies are present
        graph.updateFromModule(fileA, moduleNode, workspaceIndex)
        val dependencies = graph.getDependencies(fileA)
        // 2 regular imports + 2 static imports = 4
        assertEquals(4, dependencies.size, "Should have all 4 dependencies")

        // Verify each expected file is included
        assertTrue(dependencies.contains(URI.create("file:///src/RegularA.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/RegularZ.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/StaticA.groovy")))
        assertTrue(dependencies.contains(URI.create("file:///src/StaticZ.groovy")))
    }
}
