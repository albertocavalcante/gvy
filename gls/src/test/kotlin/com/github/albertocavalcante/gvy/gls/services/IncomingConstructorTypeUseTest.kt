// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IncomingConstructorTypeUseTest {
    enum class TypeUse { CLASS_LITERAL, IMPORT }

    @ParameterizedTest
    @EnumSource(TypeUse::class)
    fun `native indexed type use beside constructor does not become an incoming call`(typeUse: TypeUse): Unit =
        runBlocking {
            val origin = URI.create("file:///p/Local.groovy")
            val caller = URI.create("file:///Caller.groovy")
            val declaration = "package p\nclass Local {}"
            val text = if (typeUse == TypeUse.IMPORT) {
                "package use\nimport p.Local\nclass Caller { def make() { new Local() } }"
            } else {
                "package p\nclass Caller { def make() { [Local.class, new Local()] } }"
            }
            val documents = DocumentProvider().apply {
                put(origin, declaration)
                put(caller, text)
            }
            val compilation = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                compilation.compile(origin, declaration)
                compilation.compile(caller, text)
                val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
                val target = assertNotNull(index.getDocument(origin)).symbols.single { it.name == "Local" }.symbol
                val uses = assertNotNull(index.getDocument(caller)).findOccurrences(target)
                val lines = text.lines()
                fun textAt(role: OccurrenceRole): String {
                    val range = uses.single { it.role == role }.range
                    assertEquals(range.startLine, range.endLine)
                    return lines[range.startLine].substring(range.startColumn, range.endColumn)
                }
                assertTrue("Local" in textAt(OccurrenceRole.TYPE_REF))
                assertTrue("Local" in textAt(OccurrenceRole.CALL))
                val service = GroovyTextDocumentService(scope, compilation)
                val item = service.prepareCallHierarchy(
                    CallHierarchyPrepareParams(TextDocumentIdentifier(origin.toString()), Position(1, 7)),
                ).get(10, TimeUnit.SECONDS).single()
                assertEquals("Local", item.name)
                val incoming = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                    .get(10, TimeUnit.SECONDS)
                assertEquals(listOf("make"), incoming.map { it.from.name })
                assertEquals(caller.toString(), incoming.single().from.uri)
                val line = lines.lastIndex
                val name = lines.last().lastIndexOf("Local")
                assertEquals(
                    listOf(Range(Position(line, name), Position(line, name + 5))),
                    incoming.single().fromRanges,
                )
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }
}
