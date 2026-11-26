// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.MethodCallExpression
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextHandlerBinaryHintsTest {
    @Test
    fun `binary receiver never borrows parameter names from indexed source namesake`(@TempDir root: Path): Unit =
        runBlocking {
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val src = Files.createDirectory(workspace.resolve("src"))
            val peer = Files.writeString(
                src.resolve("String.groovy"),
                "package java.lang; class String { String substring(int wrong) { this } }",
            ).toUri()
            val text = "class Client { void use() { 'abc'.substring(1) } }"
            val uri = Files.writeString(root.resolve("Client.groovy"), text).toUri()
            val documents = DocumentProvider().apply { put(uri, text) }
            val compilation = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                compilation.workspaceManager.initializeWorkspace(workspace)
                compilation.getWorkspaceCompiler().compileWorkspace()
                compilation.symbolIndexingService.indexFile(peer)
                val methods = compilation.getAllSymbolStorages().values.flatMap { it.symbols.values.flatten() }
                    .filterIsInstance<Symbol.Method>()
                assertTrue(methods.any { it.uri == peer && it.name == "substring" && it.parameters.single().name == "wrong" })
                assertNotNull(compilation.getWorkspaceSymbolIndex()?.getDocument(peer))
                val held = assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri)).snapshot
                val call = held.parseResult.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
                    .single { it.methodAsString == "substring" }
                val receiver = call.objectExpression.type.redirect()
                assertFalse(receiver.isPrimaryClassNode)
                assertTrue(receiver.isResolved)
                assertEquals(String::class.java, receiver.typeClass)
                val parameter = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
                    .parameters.single().name
                assertTrue(parameter != "wrong", "the binary and source namesake must disagree")
                val service = GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
                val hints = service.inlayHint(
                    InlayHintParams(TextDocumentIdentifier(uri.toString()), Range(Position(0, 0), Position(0, text.length))),
                ).get(15, TimeUnit.SECONDS).filter { it.kind == InlayHintKind.Parameter }
                assertEquals(listOf(Position(0, text.indexOf("1")) to "$parameter:"), hints.map { it.position to it.label.left })
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }
}
