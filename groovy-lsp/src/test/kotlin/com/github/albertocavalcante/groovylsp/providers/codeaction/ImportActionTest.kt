package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class ImportActionTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var importAction: ImportAction

    private val testUri = "file:///test.groovy"

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        importAction = ImportAction(compilationService)
    }

    @Test
    fun `returns empty list when no diagnostics`() {
        val actions = importAction.createImportActions(testUri, emptyList(), "def x = 1")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `returns empty list when diagnostics not about missing symbols`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 5))
            message = "Type mismatch"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), "def x = 1")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `returns empty list when no candidates found for missing symbol`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 20))
            message = "unable to resolve class UnknownClass"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), "def x = UnknownClass.foo()")

        assertTrue(actions.isEmpty(), "Should not provide actions when no candidates found")
    }

    @Test
    fun `extracts symbol name from unable to resolve class message`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "unable to resolve class MyClass"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), "MyClass x = null")

        // We don't have any workspace symbols set up, so should be empty
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `extracts symbol name from cannot find symbol message`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "cannot find symbol MyClass"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), "MyClass x = null")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `extracts symbol name from unresolved reference message`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Unresolved reference: MyClass"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), "MyClass x = null")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `handles multiple diagnostics`() {
        val diagnostic1 = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "unable to resolve class ClassA"
        }

        val diagnostic2 = Diagnostic().apply {
            range = Range(Position(1, 0), Position(1, 10))
            message = "unable to resolve class ClassB"
        }

        val actions = importAction.createImportActions(
            testUri,
            listOf(diagnostic1, diagnostic2),
            "ClassA a = null\nClassB b = null",
        )

        // Should handle multiple diagnostics without crashing
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `creates import action when symbol found in workspace`() = runBlocking {
        // 1. Setup: Create a class in the workspace
        val libUri = URI.create("file:///src/com/example/Lib.groovy")
        val libContent = """
            package com.example
            class MyHelper { }
        """.trimIndent()
        compilationService.compile(libUri, libContent)

        // 2. Action: Request import for MyHelper
        val content = "MyHelper h = new MyHelper()"
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 8))
            message = "unable to resolve class MyHelper"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), content)

        // 3. Verify
        assertEquals(1, actions.size)
        val action = actions.first()
        assertEquals("Import 'com.example.MyHelper'", action.title)
        val edit = action.edit.changes[testUri]?.first()
        assertEquals("import com.example.MyHelper\n", edit?.newText)
    }

    @Test
    fun `finds import insertion point after package declaration`() = runBlocking {
        // Setup symbol
        val libUri = URI.create("file:///src/com/example/Target.groovy")
        compilationService.compile(libUri, "package com.example; class Target {}")

        val content = """
            package com.example
            
            class Test {
                Target t
            }
        """.trimIndent()

        val diagnostic = Diagnostic().apply {
            range = Range(Position(3, 4), Position(3, 10))
            message = "unable to resolve class Target"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), content)

        assertEquals(1, actions.size)
        val edit = actions.first().edit.changes[testUri]?.first()!!
        // Should be after package declaration (line 0 -> insert at line 1)
        assertEquals(1, edit.range.start.line)
        assertEquals(0, edit.range.start.character)
        assertEquals("import com.example.Target\n", edit.newText)
    }

    @Test
    fun `finds import insertion point after existing imports`() = runBlocking {
        // Setup symbol
        val libUri = URI.create("file:///src/com/example/Target.groovy")
        compilationService.compile(libUri, "package com.example; class Target {}")

        val content = """
            package com.example
            
            import java.util.List
            
            class Test {
                Target t
            }
        """.trimIndent()

        val diagnostic = Diagnostic().apply {
            range = Range(Position(5, 4), Position(5, 10))
            message = "unable to resolve class Target"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), content)

        assertEquals(1, actions.size)
        val edit = actions.first().edit.changes[testUri]?.first()!!
        // Should be after existing import (line 2 -> insert at line 3)
        assertEquals(3, edit.range.start.line)
        assertEquals(0, edit.range.start.character)
    }

    @Test
    fun `handles content with no package declaration`() = runBlocking {
        // Setup symbol
        val libUri = URI.create("file:///src/com/example/Target.groovy")
        compilationService.compile(libUri, "package com.example; class Target {}")

        val content = """
            class Test {
                Target t
            }
        """.trimIndent()

        val diagnostic = Diagnostic().apply {
            range = Range(Position(1, 4), Position(1, 10))
            message = "unable to resolve class Target"
        }

        val actions = importAction.createImportActions(testUri, listOf(diagnostic), content)

        assertEquals(1, actions.size)
        val edit = actions.first().edit.changes[testUri]?.first()!!
        // Should be at beginning (line 0)
        assertEquals(0, edit.range.start.line)
    }
}
