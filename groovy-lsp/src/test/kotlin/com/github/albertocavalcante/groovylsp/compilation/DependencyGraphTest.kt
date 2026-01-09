package com.github.albertocavalcante.groovylsp.compilation

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
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
        val moduleNode = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
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

        val moduleNode = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
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

        val moduleNode = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
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
        val moduleNode1 = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
        val classNode1 = ClassNode("FileB", 0, null, null, null)
        moduleNode1.addImport("FileB", classNode1)
        graph.updateFromModule(fileA, moduleNode1, workspaceIndex)

        // Second update: A depends on C (not B)
        val moduleNode2 = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
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
}
