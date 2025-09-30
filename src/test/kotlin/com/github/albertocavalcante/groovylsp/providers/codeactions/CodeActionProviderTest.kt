package com.github.albertocavalcante.groovylsp.providers.codeactions
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
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

class CodeActionProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var provider: CodeActionProvider

    @BeforeEach
    fun setup() {
        compilationService = TestUtils.createCompilationService()
        provider = CodeActionProvider()
    }

    @Test
    fun `should provide add import action for unresolved class`() {
        val code = """
            class Foo {
                def list = new ArrayList()  // ArrayList unresolved
            }
        """.trimIndent()

        val diagnostic = createDiagnostic("unable to resolve class ArrayList", 1, 19, 1, 28)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)

        assertThat(actions).hasSize(1)
        assertThat(actions[0].title).isEqualTo("Import 'java.util.ArrayList'")
        assertThat(actions[0].kind).isEqualTo(CodeActionKind.QuickFix)
        assertThat(actions[0].diagnostics).contains(diagnostic)
    }

    @Test
    fun `should provide remove import action for unused import`() {
        val code = """
            import java.util.HashMap  // unused

            class Foo {
                def list = []
            }
        """.trimIndent()

        val diagnostic = createDiagnostic("The import java.util.HashMap is never used", 0, 0, 0, 24)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)

        assertThat(actions).hasSize(1)
        assertThat(actions[0].title).isEqualTo("Remove unused import")
        assertThat(actions[0].kind).isEqualTo(CodeActionKind.QuickFix)

        // Check that it creates a proper text edit
        val edit = actions[0].edit
        assertThat(edit).isNotNull
        assertThat(edit!!.changes).containsKey("file:///test.groovy")
        val textEdits = edit.changes["file:///test.groovy"]!!
        assertThat(textEdits).hasSize(1)
        assertThat(textEdits[0].range.start.line).isEqualTo(0)
        assertThat(textEdits[0].newText).isEmpty()
    }

    @Test
    fun `should handle multiple import candidates for ambiguous class`() {
        val code = """
            class Foo {
                def date = new Date()  // Could be java.util.Date or java.sql.Date
            }
        """.trimIndent()

        val diagnostic = createDiagnostic("unable to resolve class Date", 1, 19, 1, 23)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)

        assertThat(actions).hasSizeGreaterThanOrEqualTo(2)
        val titles = actions.map { it.title }
        assertThat(titles).anyMatch { it.contains("java.util.Date") }
        assertThat(titles).anyMatch { it.contains("java.sql.Date") }
    }

    @Test
    fun `should return empty list when no diagnostic can be fixed`() {
        val code = """
            class Foo {
                def validCode = "hello"
            }
        """.trimIndent()

        val diagnostic = createDiagnostic("Some other error we don't handle", 1, 0, 1, 10)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)

        assertThat(actions).isEmpty()
    }

    @Test
    fun `should handle empty file gracefully`() {
        val code = ""

        val diagnostic = createDiagnostic("unable to resolve class Foo", 0, 0, 0, 3)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)

        // Should not throw exception and return empty or appropriate actions
        assertThat(actions).isNotNull
    }

    @Test
    fun `should create proper workspace edit for add import action`() {
        val code = """
            class Foo {
                def list = new ArrayList()
            }
        """.trimIndent()

        val diagnostic = createDiagnostic("unable to resolve class ArrayList", 1, 19, 1, 28)
        val params = createCodeActionParams(code, diagnostic)

        val actions = provider.provideCodeActions(params)
        val importAction = actions.first { it.title.contains("Import") }

        assertThat(importAction.edit).isNotNull
        val edit = importAction.edit!!
        assertThat(edit.changes).containsKey("file:///test.groovy")

        val textEdits = edit.changes["file:///test.groovy"]!!
        assertThat(textEdits).hasSize(1)
        assertThat(textEdits[0].newText).contains("import java.util.ArrayList")
        assertThat(textEdits[0].range.start.line).isEqualTo(0)
        assertThat(textEdits[0].range.start.character).isEqualTo(0)
    }

    // Helper methods for creating test data
    private fun createDiagnostic(
        message: String,
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
    ): Diagnostic = Diagnostic().apply {
        this.message = message
        this.range = Range(Position(startLine, startChar), Position(endLine, endChar))
        this.severity = DiagnosticSeverity.Error
    }

    private fun createCodeActionParams(code: String, diagnostic: Diagnostic): CodeActionParams {
        // Store the code in compilation service for testing
        val uri = URI.create("file:///test.groovy")
        runTest {
            compilationService.compile(uri, code)
        }

        return CodeActionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            range = diagnostic.range
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }
    }
}
