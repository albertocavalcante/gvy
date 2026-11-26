// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeHoverExtensionProvenanceTest {
    @Test
    fun `source receiver cannot borrow the GDK hierarchy of a binary namesake`(@TempDir root: Path): Unit =
        runBlocking {
            val classes = Files.createDirectory(root.resolve("classes"))
            val java = Files.writeString(
                root.resolve("Api.java"),
                "package p; public class Api extends java.util.ArrayList<String> {}",
            )
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), java.toString()))
            val caller = root.resolve("Client.groovy").toUri()
            val peer = root.resolve("Peer.groovy").toUri()
            val source = "class Client { def use(p.Api api) { api.reverse() } }"
            val documents = DocumentProvider().apply { put(caller, source) }
            val manager = WorkspaceManager().apply { setTestClasspath(root) { listOf(classes) } }
            val service = GroovyCompilationService(documentProvider = documents, workspaceManager = manager)
            val params = HoverParams(TextDocumentIdentifier(caller.toString()), Position(0, source.indexOf("reverse") + 1))
            try {
                val handler = HoverRequestHandler(service, documents)
                val binary = handler.getHover(params).contents.right.value
                assertContains(binary, "DefaultGroovyMethods")
                assertContains(binary, "reverse()")

                documents.put(peer, "package p; class Api {}")
                val parsed = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(caller)).snapshot.parseResult
                val selected = parsed.ast!!.classes.single().getMethods("use").single().parameters.single().type.redirect()
                assertTrue(selected.isPrimaryClassNode)
                assertEquals(Path.of(peer).toString(), selected.module.context.name)
                assertEquals("java.lang.Object", selected.superClass.name)
                val answer = handler.getHover(params).contents.right.value
                assertContains(answer, "reverse")
                assertFalse("DefaultGroovyMethods" in answer, answer)
            } finally {
                service.shutdown()
            }
        }
}
