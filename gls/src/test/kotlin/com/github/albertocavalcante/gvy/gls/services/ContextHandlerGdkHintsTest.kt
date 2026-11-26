// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.runtime.StringGroovyMethods
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextHandlerGdkHintsTest {
    @Test
    fun `string extension parameter hint uses the selected binary receiver`(@TempDir root: Path): Unit = runBlocking {
        val text = "class Client { void use(String text) { text.take(2) } }"
        val uri = root.resolve("Client.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, text) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val held = assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri)).snapshot
            val receiver = held.parseResult.ast!!.classes.single().getMethods("use").single()
                .parameters.single().type.redirect()
            assertFalse(receiver.isPrimaryClassNode)
            assertTrue(receiver.isResolved)
            assertEquals(String::class.java, receiver.typeClass)
            // Read the declared extension signature directly, independently of the inlay provider.
            val extension = StringGroovyMethods::class.java.getMethod(
                "take",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val label = extension.parameters.last().name + ":"
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val hints = service.inlayHint(
                InlayHintParams(TextDocumentIdentifier(uri.toString()), Range(Position(0, 0), Position(0, text.length))),
            ).get(15, TimeUnit.SECONDS).filter { it.kind == InlayHintKind.Parameter }
            assertEquals(listOf(Position(0, text.indexOf("2")) to label), hints.map { it.position to it.label.left })
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
