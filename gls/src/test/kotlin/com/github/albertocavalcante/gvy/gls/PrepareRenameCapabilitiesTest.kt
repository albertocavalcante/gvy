// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.RenameCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PrepareRenameCapabilitiesTest {
    @Test
    fun `existing no argument capability factory JVM method is preserved`() {
        val factory = ServerCapabilitiesFactory
        val result = factory.javaClass.getDeclaredMethod("createInitializeResult").invoke(factory) as InitializeResult
        assertTrue(result.capabilities.renameProvider.isLeft)
        assertTrue(result.capabilities.renameProvider.left == true)
    }

    @Test
    fun `rename preparation is advertised only to clients that support it`(): Unit = runBlocking {
        val server = GroovyLanguageServer()
        try {
            val result = server.initialize(parameters(true)).await()

            assertTrue(result.capabilities.renameProvider.isRight)
            assertTrue(result.capabilities.renameProvider.right.prepareProvider == true)
        } finally {
            server.shutdown().await()
        }
    }

    @Test
    fun `legacy and explicitly unsupported clients retain boolean rename support`(): Unit = runBlocking {
        for (supported in listOf(null, false)) {
            val server = GroovyLanguageServer()
            try {
                val result = server.initialize(parameters(supported)).await()

                assertTrue(result.capabilities.renameProvider.isLeft)
                assertTrue(result.capabilities.renameProvider.left == true)
            } finally {
                server.shutdown().await()
            }
        }
    }

    private fun parameters(supported: Boolean?): InitializeParams {
        val rename = RenameCapabilities().apply { prepareSupport = supported }
        val textDocument = TextDocumentClientCapabilities().apply { this.rename = rename }
        return InitializeParams().apply {
            processId = null
            capabilities = ClientCapabilities().apply { this.textDocument = textDocument }
        }
    }
}
