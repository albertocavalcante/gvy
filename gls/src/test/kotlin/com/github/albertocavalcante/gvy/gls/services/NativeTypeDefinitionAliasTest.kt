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
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NativeTypeDefinitionAliasTest {
    @Test
    fun `an inferred local keeps its selected source parameter through an alias`(@TempDir root: Path) = runBlocking {
        val uri = root.resolve("Client.groovy").toUri()
        val text = """
            class Product {}
            class Client {
                def run(Product input) {
                    def alias = input
                    alias
                }
            }
        """.trimIndent()
        val docs = DocumentProvider().apply { put(uri, text) }
        val compilation = GroovyCompilationService(documentProvider = docs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val parsed = assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri)).snapshot.parseResult
            val classes = assertNotNull(parsed.ast).classes
            val product = classes.single { it.name == "Product" }
            val run = classes.single { it.name == "Client" }.getDeclaredMethods("run").single()
            val input = run.parameters.single()
            val initialization = parsed.astModel.getAllNodes().filterIsInstance<DeclarationExpression>()
                .single { it.variableExpression.name == "alias" }
            assertSame(input, assertIs<VariableExpression>(initialization.rightExpression).accessedVariable)
            assertSame(product, input.type.redirect())
            val line = text.lineSequence().elementAt(4)
            val params = TypeDefinitionParams(TextDocumentIdentifier(uri.toString()), Position(4, line.indexOf("alias") + 1))
            val location = GroovyTextDocumentService(scope, compilation).typeDefinition(params)
                .get(15, TimeUnit.SECONDS).left.single()
            assertEquals(uri.toString(), location.uri)
            assertEquals(Range(Position(0, 0), Position(0, "class Product {}".length)), location.range)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
