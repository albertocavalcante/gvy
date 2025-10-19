package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroovyLanguageServerCapabilitiesContractTest {

    private lateinit var server: GroovyLanguageServer
    private lateinit var client: SynchronizingTestLanguageClient

    @BeforeEach
    fun setUp() {
        server = GroovyLanguageServer()
        client = SynchronizingTestLanguageClient()
        server.connect(client)
    }

    @Test
    fun `server capabilities align with supported features`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("groovy-lsp-contract")
        val params = InitializeParams().apply {
            processId = 42
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "contract"))
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("ContractTestClient", "1.0.0")
        }

        val result = server.initialize(params).get()
        server.initialized(InitializedParams())

        val capabilities = result.capabilities
        assertNotNull(capabilities)

        // Text document sync
        val syncKind = capabilities.textDocumentSync?.left
        assertEquals(TextDocumentSyncKind.Full, syncKind)

        // Advertised features should be enabled
        assertNotNull(capabilities.completionProvider)
        assertTrue(capabilities.hoverProvider?.left == true)
        assertTrue(capabilities.definitionProvider?.left == true)
        assertTrue(capabilities.referencesProvider?.left == true)
        assertTrue(capabilities.documentSymbolProvider?.left == true)
        assertTrue(capabilities.workspaceSymbolProvider?.left == true)
        assertTrue(capabilities.typeDefinitionProvider?.left == true)
        assertTrue(capabilities.documentFormattingProvider?.left == true)

        // Features not yet implemented must remain disabled
        assertNull(capabilities.renameProvider, "Rename is not implemented and should not be advertised.")
        assertNull(capabilities.codeActionProvider, "Code actions are not implemented and should not be advertised.")
        assertNull(
            capabilities.signatureHelpProvider,
            "Signature help is not implemented and should not be advertised.",
        )
    }
}
