// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AstSnapshotIntegrationTest {
    @Test
    fun `cold and warm requests use the same synchronized text without publishing it`(): Unit = runBlocking {
        val uri = URI("file:///AstSnapshot.groovy")
        val old = "class Old {}\n"
        val source = "class Current { String face = '😀' }\r\n"
        val documents = DocumentProvider().apply { put(uri, source, 7) }
        val compiler = GroovyCompilationService(documentProvider = documents)
        val handler = AstRequestHandler(compiler, CoreAstConverter(), NativeAstConverter(), documents, this)
        try {
            val cold = listOf("native", "core").associateWith { parser ->
                handler.getAst(AstParams(uri.toString(), parser)).await()
            }
            assertNull(compiler.getCompiledSnapshot(uri))
            assertTrue(compiler.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(uri) != true)
            documents.put(uri, old, 6)
            compiler.compile(uri, old)
            assertEquals(old, assertNotNull(compiler.getCompiledSnapshot(uri)).sourceText)
            documents.update(uri, source, 7)
            val warm = listOf("native", "core").associateWith { parser ->
                handler.getAst(AstParams(uri.toString(), parser)).await()
            }
            assertEquals(old, assertNotNull(compiler.getCompiledSnapshot(uri)).sourceText)
            for (parser in listOf("native", "core")) {
                val first = cold.getValue(parser)
                val second = warm.getValue(parser)
                assertEquals(first.sourceHash, second.sourceHash)
                assertEquals(7, second.documentVersion)
                for (result in listOf(first, second)) {
                    val tree = Json.decodeFromString<AstNodeDto>(result.ast)
                    assertTrue(tree.children.any { it.properties["name"] == "Current" }, parser)
                    assertTrue(tree.children.none { it.properties["name"] == "Old" }, parser)
                }
            }
        } finally {
            compiler.clearCaches()
        }
    }

    @Test
    fun `encoded target URI never adds its disk source to the requested buffer`(@TempDir root: Path): Unit =
        runBlocking {
            val path = root.resolve("Alias.groovy")
            Files.writeString(path, "class Alias { String diskOnly }\n")
            val uri = URI(path.toUri().toString().replace("Alias.groovy", "%41lias.groovy"))
            val source = "class Alias { String bufferOnly }\n"
            val documents = DocumentProvider().apply { put(uri, source, 1) }
            val compiler = GroovyCompilationService(documentProvider = documents)
            compiler.updateWorkspaceModel(root, emptyList(), listOf(root))
            assertTrue(path in compiler.workspaceManager.current().sources)
            val handler = AstRequestHandler(compiler, CoreAstConverter(), NativeAstConverter(), documents, this)
            try {
                val parsed = compiler.compileTransient(uri, source)
                assertTrue(parsed.isSuccessful)
                assertTrue(!parsed.outcome.isDegraded, parsed.outcome.toString())
                for (parser in listOf("native", "core")) {
                    val result = handler.getAst(AstParams(uri.toString(), parser)).await()
                    val tree = Json.decodeFromString<AstNodeDto>(result.ast)
                    val cls = tree.children.single { it.properties["name"] == "Alias" }
                    assertTrue(cls.children.any { it.properties["name"] == "bufferOnly" }, parser)
                    assertTrue(cls.children.none { it.properties["name"] == "diskOnly" }, parser)
                }
            } finally {
                compiler.clearCaches()
            }
        }
}
