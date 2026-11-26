// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class NativeQueryParameterHintsTest {
    @Test
    fun `selected unsaved peer supplies its constructor and overload labels`(@TempDir root: Path) {
        val peer = "package a\nclass Peer { Peer(String title) {}\n" +
            "void send(String text) {}\nvoid send(int count) {} }"
        assertEquals(listOf("title:", "count:"), labels(root, peer, "new a.Peer('ok').send(2)"))
    }

    @Test
    fun `unresolved qualified construction cannot borrow another package's constructor`(@TempDir root: Path) {
        val peer = "package a\nclass Peer { Peer(String wrong) {} }"
        assertEquals(emptyList(), labels(root, peer, "new missing.Peer('ok')"))
    }

    private fun labels(root: Path, peer: String, call: String): List<String> {
        val path = Files.writeString(root.resolve("Peer.groovy"), "package a\nclass Peer { Peer(String disk) {} }")
        val uri = root.resolve("Client.groovy").toUri()
        val text = "class Client { void run() { $call } }"
        val docs = DocumentProvider()
        val workspace = WorkspaceManager()
        val compilation = GroovyCompilationService(documentProvider = docs, workspaceManager = workspace)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            workspace.initializeWorkspace(root)
            compilation.onDocumentOpened(path.toUri())
            docs.put(path.toUri(), peer)
            compilation.onDocumentOpened(uri)
            docs.put(uri, text)
            runBlocking { compilation.compile(path.toUri(), peer) }
            val service =
                GroovyTextDocumentService(scope, compilation, GroovyTextDocumentServiceOptions(documentProvider = docs))
            val params =
                InlayHintParams(TextDocumentIdentifier(uri.toString()), Range(Position(0, 0), Position(0, text.length)))
            return service.inlayHint(params).get(15, TimeUnit.SECONDS)
                .filter { it.kind == InlayHintKind.Parameter }.sortedBy { it.position.character }.map { it.label.left }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
