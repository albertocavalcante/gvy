package com.github.albertocavalcante.groovylsp.services
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.test.MockConfigurationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class GroovyTextDocumentServiceTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var textDocumentService: GroovyTextDocumentService
    private lateinit var coroutineScope: CoroutineScope

    @BeforeEach
    fun setup() {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        compilationService = TestUtils.createCompilationService()
        textDocumentService = GroovyTextDocumentService(
            coroutineScope,
            compilationService,
            MockConfigurationProvider(),
        ) { null } // No client for tests
    }

    @Test
    fun `should provide code actions for unresolved class through text document service`() = runBlocking {
        val code = """
            class Foo {
                def list = new ArrayList()  // Unresolved
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile to get real diagnostics
        @Suppress("UNUSED_VARIABLE")
        val compilationResult = compilationService.compile(uri, code)

        // Create diagnostic similar to what compilation would produce
        val diagnostic = Diagnostic().apply {
            message = "unable to resolve class ArrayList"
            range = Range(Position(1, 19), Position(1, 28))
            severity = DiagnosticSeverity.Error
        }

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = diagnostic.range
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }

        val result = textDocumentService.codeAction(params).get()

        assertThat(result).isNotEmpty()
        val action = result[0]
        assertThat(action.isRight).isTrue()

        val codeAction = action.right
        assertThat(codeAction.title).isEqualTo("Import 'java.util.ArrayList'")
        assertThat(codeAction.kind).isEqualTo(CodeActionKind.QuickFix)
        assertThat(codeAction.edit).isNotNull()
    }

    @Test
    fun `should provide remove import action for unused imports`() = runBlocking {
        val code = """
            import java.util.HashMap  // Unused

            class Foo {
                def list = []
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, code)

        val diagnostic = Diagnostic().apply {
            message = "The import java.util.HashMap is never used"
            range = Range(Position(0, 0), Position(0, 24))
            severity = DiagnosticSeverity.Warning
        }

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = diagnostic.range
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }

        val result = textDocumentService.codeAction(params).get()

        assertThat(result).hasSize(1)
        val codeAction = result[0].right
        assertThat(codeAction.title).isEqualTo("Remove unused import")
        assertThat(codeAction.kind).isEqualTo(CodeActionKind.QuickFix)

        // Verify the edit removes the import line
        val edit = codeAction.edit!!
        val textEdits = edit.changes["file:///test.groovy"]!!
        assertThat(textEdits).hasSize(1)
        assertThat(textEdits[0].newText).isEmpty()
        assertThat(textEdits[0].range.start.line).isEqualTo(0)
    }

    @Test
    fun `should return multiple import candidates for ambiguous classes`() = runBlocking {
        val code = """
            class Foo {
                def date = new Date()  // Ambiguous
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, code)

        val diagnostic = Diagnostic().apply {
            message = "unable to resolve class Date"
            range = Range(Position(1, 19), Position(1, 23))
            severity = DiagnosticSeverity.Error
        }

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = diagnostic.range
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }

        val result = textDocumentService.codeAction(params).get()

        assertThat(result).hasSizeGreaterThanOrEqualTo(2)
        val titles = result.map { it.right.title }
        assertThat(titles).anyMatch { it.contains("java.util.Date") }
        assertThat(titles).anyMatch { it.contains("java.sql.Date") }
    }

    @Test
    fun `should return empty list when no code actions available`() = runBlocking {
        val code = """
            class Foo {
                def validCode = "hello"
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, code)

        val diagnostic = Diagnostic().apply {
            message = "Some unknown error type"
            range = Range(Position(1, 0), Position(1, 10))
            severity = DiagnosticSeverity.Information
        }

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = diagnostic.range
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }

        val result = textDocumentService.codeAction(params).get()

        assertThat(result).isEmpty()
    }

    @Test
    fun `should handle empty diagnostics gracefully`() = runBlocking {
        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = Range(Position(0, 0), Position(0, 0))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val result = textDocumentService.codeAction(params).get()

        assertThat(result).isEmpty()
    }
}
