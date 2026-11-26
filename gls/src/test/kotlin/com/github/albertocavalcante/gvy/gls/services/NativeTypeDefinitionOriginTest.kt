// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeTypeDefinitionOriginTest {
    @Test
    fun `inferred locals retain which same-named type their initializer actually selected`(@TempDir root: Path) =
        runBlocking {
            val uri = root.resolve("Client.groovy").toUri()
            val text = """
                package java.math
                class BigDecimal {}
                class Client {
                    def run() {
                        def binary = 1.25
                        def local = new BigDecimal()
                        [binary, local]
                    }
                }
            """.trimIndent()
            val docs = DocumentProvider().apply { put(uri, text) }
            val compilation = GroovyCompilationService(documentProvider = docs)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val snapshot = assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri)).snapshot
                val result = snapshot.parseResult
                val module = assertNotNull(result.ast)
                val localClass = module.classes.single { it.name == BigDecimal::class.java.name }
                val declarations = result.astModel.getAllNodes().filterIsInstance<DeclarationExpression>()
                    .associateBy { it.variableExpression.name }
                val binary = assertIs<ConstantExpression>(declarations.getValue("binary").rightExpression)
                    .type.redirect()
                val local = assertIs<ConstructorCallExpression>(declarations.getValue("local").rightExpression)
                    .type.redirect()
                assertTrue(local.isPrimaryClassNode)
                assertSame(localClass, local)
                assertEquals(uri, local.module.context.inputDocumentUri())
                assertFalse(binary.isPrimaryClassNode)
                assertTrue(binary.isResolved)
                assertEquals(BigDecimal::class.java, binary.typeClass)
                assertEquals(local.name, binary.name)
                val service = GroovyTextDocumentService(scope, compilation)
                val line = text.lineSequence().elementAt(6)
                fun locations(name: String) = service.typeDefinition(
                    TypeDefinitionParams(TextDocumentIdentifier(uri.toString()), Position(6, line.indexOf(name) + 1)),
                ).get(15, TimeUnit.SECONDS).left
                val selected = locations("local").single()
                assertEquals(uri.toString(), selected.uri)
                assertEquals(Range(Position(1, 0), Position(1, "class BigDecimal {}".length)), selected.range)
                assertTrue(locations("binary").isEmpty(), "the decimal literal's binary class has no local definition")
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }
}
