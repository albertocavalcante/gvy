// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.jenkins

import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedAstParser
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseResult
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenkinsVisibleVariablesTest {
    @Test
    fun `script root and nested local declarations are visible in the current closure`() {
        val names = visibleNames(
            """
            def rootName = 'root'
            node {
                def localName = 'local'
                def (first, second) = [1, 2]
                marker()
                def futureName = 'later'
            }
            """.trimIndent(),
        )
        assertTrue(names.containsAll(setOf("rootName", "localName", "first", "second")), names.toString())
        assertFalse("futureName" in names)
    }

    @Test
    fun `sibling locals and script run locals do not leak into helper methods`() {
        val sibling = visibleNames("node { def sibling = 1 }; node { marker() }")
        assertFalse("sibling" in sibling)
        val helper = visibleNames("def rootName = 1; def helper(arg) { marker() }")
        assertTrue("arg" in helper)
        assertFalse("rootName" in helper)
    }

    @Test
    fun `closure and method parameters and loop and catch variables are visible only in their bodies`() {
        val loop = visibleNames("def helper(param) { node { closureArg -> for (item in [1]) { marker() } } }")
        assertTrue(loop.containsAll(setOf("param", "closureArg", "item")), loop.toString())
        val caught = visibleNames("try { work() } catch (Exception problem) { marker() }")
        assertTrue("problem" in caught, caught.toString())
        val after = visibleNames("try { work() } catch (Exception problem) { work() }; marker()")
        assertFalse("problem" in after)
    }

    @Test
    fun `native compiled script root variable is visible in closure but not helper method`() = runBlocking {
        val source = """
            def rootName = 'root'
            node { rootProbe() }
            def helper() { helperProbe() }
        """.trimIndent()
        val uri = URI("file:///Jenkinsfile")
        val compilation = GroovyCompilationService()
        try {
            assertTrue(compilation.compile(uri, source).isSuccess)
            val parsed = assertNotNull(compilation.getParseResult(uri))
            val model = parsed.astModel
            val index = TextIndex(source)
            fun namesAt(callName: String): Set<String> {
                val call = model.getNodes(uri).filterIsInstance<MethodCallExpression>()
                    .single { it.methodAsString == callName }
                val position = requireNotNull(index.utf16Position(Position(call.lineNumber - 1, call.columnNumber - 1)))
                return JenkinsVisibleVariables.find(model, uri, call, position, index)
                    .map { it.name }.toSet()
            }
            assertTrue("rootName" in namesAt("rootProbe"))
            assertFalse("rootName" in namesAt("helperProbe"))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `implicit it is visible in implicit parameter closures only`() {
        assertTrue("it" in visibleNames("node { marker() }"))
        assertFalse("it" in visibleNames("node { -> marker() }"))
    }

    @Test
    fun `a real parameter with the loop dummy name remains visible`() {
        val names = visibleNames("def helper(forLoopDummyParameter) { marker() }")
        assertTrue("forLoopDummyParameter" in names, names.toString())
    }

    @Test
    fun `future same-line declarations after Unicode are not visible`() {
        val source = "def label = '😀😀😀😀😀😀😀😀😀😀'; marker();def futureName = 1"
        val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
        val uri = URI("file:///Jenkinsfile")
        val model = RecursiveAstVisitor(NodeRelationshipTracker())
        model.visitModule(parsed.module, uri)
        val call = model.getNodes(uri).filterIsInstance<MethodCallExpression>().single { it.methodAsString == "marker" }
        val markerColumn = source.indexOf("marker()")

        val names = JenkinsVisibleVariables.find(model, uri, call, Position(0, markerColumn), TextIndex(source)).map {
            it.name
        }

        assertTrue("label" in names, names.toString())
        assertFalse("futureName" in names, names.toString())
    }

    @Test
    fun `script locals are not visible in ordinary classes`() {
        val names = visibleNames("def rootName = 1; class Helper { def work() { marker() } }")
        assertFalse("rootName" in names)
    }

    private fun visibleNames(source: String): Set<String> {
        val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
        val uri = URI("file:///Jenkinsfile")
        val model = RecursiveAstVisitor(NodeRelationshipTracker())
        model.visitModule(parsed.module, uri)
        val call = model.getNodes(uri).filterIsInstance<MethodCallExpression>().single { it.methodAsString == "marker" }
        val index = TextIndex(source)
        val position = requireNotNull(index.utf16Position(Position(call.lineNumber - 1, call.columnNumber - 1)))
        return JenkinsVisibleVariables.find(model, uri, call, position, index)
            .map { it.name }.toSet()
    }
}
