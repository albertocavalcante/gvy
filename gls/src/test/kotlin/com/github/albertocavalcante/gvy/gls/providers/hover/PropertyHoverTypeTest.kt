// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PropertyHoverTypeTest {
    @Test
    fun `declared property hover resolves constructor inferred receiver and String value`(): Unit = runBlocking {
        withProperty(
            """
            class Greeter { String message = 'Hi' }
            def greeter = new Greeter()
            greeter.message
            """.trimIndent(),
        ) { fixture ->
            assertEquals("java.lang.Object", fixture.expression.objectExpression.type.name)
            assertEquals("java.lang.Object", fixture.expression.type.name)
            assertEquals(
                SemanticType.Known("Greeter"),
                fixture.resolver.resolveType(fixture.expression.objectExpression, fixture.module),
            )
            assertEquals(
                SemanticType.Known("java.lang.String"),
                fixture.resolver.resolveType(fixture.expression, fixture.module),
            )

            val content = fixture.hover()
            assertTrue(content.contains("**Object Type**: Greeter"), content)
            assertTrue(content.contains("**Property Type**: String"), content)
        }
    }

    @Test
    fun `dynamic property initializer does not become a declared String hover`(): Unit = runBlocking {
        withProperty(
            """
            class Greeter { def message = 'Hi' }
            def greeter = new Greeter()
            greeter.message
            """.trimIndent(),
        ) { fixture ->
            assertEquals(
                SemanticType.Known("Greeter"),
                fixture.resolver.resolveType(fixture.expression.objectExpression, fixture.module),
            )
            assertTrue(fixture.resolver.resolveType(fixture.expression, fixture.module) is SemanticType.Dynamic)

            val content = fixture.hover()
            assertFalse(content.contains("**Property Type**: String"), content)
            assertFalse(content.contains("**Property Type**: Object"), content)
        }
    }

    @Test
    fun `untyped parameter does not borrow a same named receiver from another method`(): Unit = runBlocking {
        withProperty(
            """
            class Greeter { String message = 'Hi' }
            def typed(Greeter greeter) { greeter.message }
            def untyped(greeter) { greeter.message }
            """.trimIndent(),
        ) { fixture ->
            assertTrue(
                fixture.resolver.resolveType(
                    fixture.expression.objectExpression,
                    fixture.module,
                ) is SemanticType.Dynamic,
            )
            assertTrue(fixture.resolver.resolveType(fixture.expression, fixture.module) is SemanticType.Unknown)

            val content = fixture.hover()
            assertTrue(content.contains("greeter.message"), content)
            assertFalse(content.contains("**Property Type**: String"), content)
            assertFalse(content.contains("**Object Type**: Greeter"), content)
        }
    }

    @Test
    fun `explicit java Object expression type remains visible instead of being treated as unknown`() {
        val resolver = SemanticTypeResolver(ReflectionTypeSolver())
        val expression = PropertyExpression(VariableExpression("receiver"), "message").apply {
            type = ClassHelper.OBJECT_TYPE
        }
        assertFalse(ClassHelper.isDynamicTyped(expression.type))

        val hover = assertNotNull(HoverContentGenerator(resolver).generateHover(expression).getOrNull())
        val content = hover.contents.right.value

        assertTrue(content.contains("**Property Type**: Object"), content)
        assertFalse(content.contains("**Property Type**: String"), content)
    }

    private suspend fun withProperty(source: String, verify: suspend (PropertyFixture) -> Unit) {
        val uri = URI("file:///PropertyHover.groovy")
        val documents = DocumentProvider().apply { put(uri, source, 1) }
        val compiler = GroovyCompilationService(documentProvider = documents)
        var parsed: ParseResult? = null
        try {
            compiler.compile(uri, source, Phases.CANONICALIZATION)
            val snapshot = assertNotNull(compiler.getCompiledSnapshot(uri))
            val result = snapshot.parseResult
            parsed = result
            val module = assertNotNull(result.ast)
            assertEquals(Phases.CANONICALIZATION, result.sourceUnit.phase)
            assertTrue(result.sourceUnit.isPhaseComplete)
            assertFalse(result.compilationUnit.errorCollector.hasErrors())
            assertTrue(result.outcome.stubbedClasses.isEmpty())
            val line = source.lines().indexOfLast { it.contains("greeter.message") }
            val expression = result.astModel.getNodes(uri).filterIsInstance<PropertyExpression>().single {
                it.lineNumber == line + 1 && it.propertyAsString == "message"
            }
            val resolver =
                SemanticTypeResolver(compiler.classpathService.getTypeSolver(), compiler.getWorkspaceSymbolIndex())
            val provider = HoverProvider(compiler, documents, HoverContentGenerator(resolver))
            val position = Position(line, source.lines()[line].lastIndexOf("message") + 2)
            println(
                "property hover witness: rawReceiver=${expression.objectExpression.type.name}, " +
                    "rawProperty=${expression.type.name}, " +
                    "receiver=${resolver.resolveType(expression.objectExpression, module)}, " +
                    "property=${resolver.resolveType(expression, module)}",
            )
            verify(PropertyFixture(uri, position, expression, module, resolver, provider))
        } finally {
            compiler.clearCaches()
            parsed?.compilationUnit?.classLoader?.close()
        }
    }

    private data class PropertyFixture(
        val uri: URI,
        val position: Position,
        val expression: PropertyExpression,
        val module: ModuleNode,
        val resolver: SemanticTypeResolver,
        val provider: HoverProvider,
    ) {
        suspend fun hover(): String {
            val hover = assertNotNull(provider.provideHover(uri.toString(), position))
            assertTrue(hover.contents.isRight)
            assertEquals(MarkupKind.MARKDOWN, hover.contents.right.kind)
            return hover.contents.right.value
        }
    }
}
