package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroovyLanguageServerFeatureMatrixTest {

    private lateinit var server: GroovyLanguageServer
    private lateinit var client: SynchronizingTestLanguageClient
    private lateinit var workspaceRoot: Path
    private lateinit var documentUri: String

    private val documentContent = """
        class Greeter{
            String message="Hi"
            void greet(){
                println message
            }
        }

        def greeter = new Greeter()
        greeter.message
        greeter.greet()
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        workspaceRoot = Files.createTempDirectory("groovy-lsp-feature-matrix")
        documentUri = workspaceRoot.resolve("feature-matrix.groovy").toUri().toString()

        server = GroovyLanguageServer()
        client = SynchronizingTestLanguageClient()
        server.connect(client)

        val initParams = InitializeParams().apply {
            processId = 99
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "feature-matrix"))
        }

        runBlocking {
            server.initialize(initParams).get()
            server.initialized(InitializedParams())
        }

        client.awaitMessageMatching { it.message.startsWith("Dependencies loaded:") }

        val textDocument = TextDocumentItem(documentUri, "groovy", 1, documentContent)
        server.textDocumentService.didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams(textDocument))

        client.awaitSuccessfulCompilation(documentUri)
    }

    @Test
    fun `completion provider returns static suggestions`() = runBlocking {
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(7, 0)
        }

        val result = server.textDocumentService.completion(params).get()
        assertTrue(result.isLeft)
        val items = result.left
        assertTrue(items.isNotEmpty(), "Expected completion items for empty position.")
    }

    @Test
    fun `definition provider resolves class constructor reference`() = runBlocking {
        val params = DefinitionParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(7, 25) // On "Greeter" constructor call
        }

        val result = server.textDocumentService.definition(params).get()
        assertTrue(result.isLeft)
        val locations = result.left
        assertTrue(locations.isNotEmpty(), "Definition request should return at least one location.")
        assertTrue(
            locations.any { it.uri == documentUri },
            "Definition should resolve within the current document.",
        )
    }

    @Test
    fun `references provider finds variable usages`() = runBlocking {
        val params = ReferenceParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(7, 4) // Over "greeter" variable declaration
            context = ReferenceContext(true)
        }

        val locations = server.textDocumentService.references(params).get()
        assertTrue(locations.isNotEmpty(), "Expected references for 'greeter'.")
        assertTrue(locations.size >= 2, "Should include declaration and usages.")
    }

    @Test
    fun `hover provider returns markdown`() = runBlocking {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(8, 2) // Over "greeter"
        }

        val hover = server.textDocumentService.hover(params).get()
        assertNotNull(hover.contents)
        assertTrue(hover.contents.isRight)
    }

    @Test
    fun `type definition resolves to class declaration`() = runBlocking {
        val params = TypeDefinitionParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(7, 25)
        }

        val either = server.textDocumentService.typeDefinition(params).get()
        assertTrue(either.isLeft)
        val locations = either.left
        assertTrue(locations.isNotEmpty(), "Type definition should return at least one location.")
    }

    @Test
    fun `document symbols return symbols`() = runBlocking {
        val params = DocumentSymbolParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
        }
        val symbols = server.textDocumentService.documentSymbol(params).get()
        assertTrue(symbols.isNotEmpty(), "Document symbols should be provided for the file.")
    }

    @Test
    @Suppress("DEPRECATION")
    fun `workspace symbol search returns matches`() = runBlocking {
        val result: Either<List<SymbolInformation>, List<WorkspaceSymbol>> =
            server.workspaceService.symbol(WorkspaceSymbolParams("Greeter")).get()
        assertTrue(result.isLeft)
        assertTrue(result.left.any { it.name.contains("Greeter") })
    }

    @Test
    fun `rename operation updates variable references`() {
        runBlocking {
            val params = RenameParams().apply {
                textDocument = TextDocumentIdentifier(documentUri)
                position = Position(7, 4) // On "greeter" variable declaration
                newName = "updatedGreeter"
            }

            val workspaceEdit = server.textDocumentService.rename(params).get()
            assertNotNull(workspaceEdit)
            assertNotNull(workspaceEdit.documentChanges)
            assertTrue(workspaceEdit.documentChanges.isNotEmpty(), "Rename should produce document changes")

            // Verify that the changes are for the correct document
            val textEdits = workspaceEdit.documentChanges
                .filter { it.isLeft }
                .map { it.left }
                .filter { it.textDocument.uri == documentUri }

            assertTrue(textEdits.isNotEmpty(), "Should have text edits for the document")

            // Verify that edits contain the new name
            val allEdits = textEdits.flatMap { it.edits }
            assertTrue(allEdits.all { it.newText == "updatedGreeter" }, "All edits should use the new name")
            assertTrue(allEdits.size >= 3, "Should rename declaration and all usages")
        }
    }

    @Test
    fun `formatting returns edits for unformatted document`() = runBlocking {
        val params = org.eclipse.lsp4j.DocumentFormattingParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            options = org.eclipse.lsp4j.FormattingOptions(4, true)
        }

        val edits = server.textDocumentService.formatting(params).get()
        assertTrue(edits.isNotEmpty(), "Formatter should produce at least one edit for unformatted content.")
    }
}
