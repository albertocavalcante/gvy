// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HoverMemberDeliveryTest {
    @Test
    fun `caller qualification cannot replace the consulted peer member publication`(@TempDir root: Path): Unit =
        runBlocking {
            val peerText = "class Peer { String work() { 'ok' } }"
            val text = "class Client { def use(Peer peer) { def result = peer.work(); result } }"
            val peer = Files.writeString(root.resolve("Peer.groovy"), peerText).toUri()
            val uri = Files.writeString(root.resolve("Client.groovy"), text).toUri()
            val docs = DocumentProvider().apply {
                put(peer, peerText)
                put(uri, text)
            }
            val manager = spyk(WorkspaceManager())
            val service = GroovyCompilationService(documentProvider = docs, workspaceManager = manager)
            try {
                manager.initializeWorkspace(root)
                service.compile(peer, peerText)
                val index = assertNotNull(service.getWorkspaceSymbolIndex())
                val original = assertNotNull(index.getDocument(peer))
                val qualified = AtomicBoolean(false)
                val replaced = AtomicBoolean(false)
                val race = AtomicBoolean(false)
                every { manager.compileContextFor(any(), any(), any()) } answers {
                    val context = callOriginal()
                    when (firstArg<URI>()) {
                        peer -> qualified.set(true)
                        uri -> if (race.get() && qualified.get() && replaced.compareAndSet(false, true)) {
                            index.updateDocument(peer, original.copy(), peerText)
                        }
                    }
                    context
                }
                val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, text.indexOf("result") + 1))
                val handler = HoverRequestHandler(service, docs)
                assertContains(handler.getHover(params).contents.right.value, "String")
                assertTrue(qualified.get(), "The real native provider must qualify the foreign member producer")
                qualified.set(false)
                race.set(true)
                val error = runCatching { handler.getHover(params) }.exceptionOrNull()
                assertTrue(replaced.get(), "Caller qualification must run after the peer lookup")
                assertEquals(ResponseErrorCode.ContentModified.value, assertIs<ResponseErrorException>(error).responseError.code)
            } finally {
                service.shutdown()
            }
        }
}
