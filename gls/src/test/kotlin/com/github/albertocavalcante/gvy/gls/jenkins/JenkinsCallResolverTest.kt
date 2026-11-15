// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.jenkins

import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedAstParser
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseResult
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsRegion
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsSourceContext
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedDeclarativeOption
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class JenkinsCallResolverTest {
    @Test
    fun `scripted calls resolve as steps even outside node`() {
        assertKind(JenkinsCallKind.STEP, "timeout(time: 2) { echo 'x' }", "timeout")
        assertKind(JenkinsCallKind.STEP, "this.timeout(time: 2) {}", "timeout")
    }

    @Test
    fun `declarative option and executable step sharing a name remain distinct`() {
        assertKind(JenkinsCallKind.OPTION, "pipeline { options { timeout(time: 2) } }", "timeout")
        assertKind(
            JenkinsCallKind.STEP,
            "pipeline { stages { stage('x') { steps { timeout(time: 2) {} } } } }",
            "timeout",
        )
    }

    @Test
    fun `supplementary characters before a declarative option preserve its context`() {
        assertParsedKind(
            JenkinsCallKind.OPTION,
            "echo('🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀'); pipeline { options { timeout(time: 1) } }",
            "timeout",
        )
    }

    @Test
    fun `object receiver is not a pipeline step`() {
        assertKind(JenkinsCallKind.LOCAL, "client.timeout()", "timeout")
    }

    @Test
    fun `script method shadows a Jenkins step`() {
        val script = ClassNode("Jenkinsfile", 1, ClassHelper.OBJECT_TYPE).apply {
            isScript = true
            addMethod(
                "timeout",
                1,
                ClassHelper.OBJECT_TYPE,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                BlockStatement(),
            )
        }
        val call = MethodCallExpression(VariableExpression("this"), "timeout", ArgumentListExpression()).apply {
            isImplicitThis = true
            lineNumber = 1
            columnNumber = 1
        }
        val model = mockk<GroovyAstModel> {
            every { getNodes(any()) } returns emptyList()
            every { getUri(call) } returns URI("file:///Jenkinsfile")
            every { getParent(call) } returns script
            every { getParent(script) } returns null
        }
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(model, URI("file:///Jenkinsfile"), JenkinsSourceContext("timeout()")),
            metadata(),
        )
        assertEquals(JenkinsCallKind.LOCAL, result.kind)
    }

    @Test
    fun `steps parameter is an ordinary receiver`() {
        val receiver = VariableExpression(Parameter(ClassHelper.OBJECT_TYPE, "steps"))
        val call = MethodCallExpression(receiver, "timeout", ArgumentListExpression()).apply {
            lineNumber = 1
            columnNumber = 1
        }
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(null, URI("file:///Jenkinsfile"), JenkinsSourceContext("steps.timeout()")),
            metadata(),
        )
        assertEquals(JenkinsCallKind.LOCAL, result.kind)
    }

    @Test
    fun `library global resolves only when receiver and scope permit`() {
        val source = "deploy()"
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression()).apply {
            isImplicitThis = true
            lineNumber = 1
            columnNumber = 1
        }
        val script = ClassNode("Jenkinsfile", 1, ClassHelper.OBJECT_TYPE).apply {
            isScript = true
            addField("deploy", 1, ClassHelper.OBJECT_TYPE, null)
        }
        val model = mockk<GroovyAstModel> {
            every { getNodes(any()) } returns emptyList()
            every { getUri(call) } returns URI("file:///Jenkinsfile")
            every { getParent(call) } returns script
            every { getParent(script) } returns null
        }
        val context = JenkinsSourceContext(source)
        val uri = URI("file:///Jenkinsfile")
        assertParsedKind(JenkinsCallKind.LIBRARY_GLOBAL, source, "deploy", varsNames = setOf("deploy"))
        assertEquals(
            JenkinsCallKind.LOCAL,
            JenkinsCallResolver.resolve(
                call,
                JenkinsCallContext(model, uri, context),
                metadata(),
                setOf("deploy"),
            ).kind,
        )
    }

    @Test
    fun `parsed local method shadows a step`() {
        assertParsedKind(JenkinsCallKind.LOCAL, "def timeout() {}\ntimeout()", "timeout")
    }

    @Test
    fun `script root closure declaration shadows a step`() {
        assertParsedKind(JenkinsCallKind.LOCAL, "def timeout = {}\ntimeout()", "timeout")
    }

    @Test
    fun `script root local is not visible in an explicit helper method`() {
        assertParsedKind(JenkinsCallKind.STEP, "def timeout = {}\ndef helper() { timeout() }", "timeout")
    }

    @Test
    fun `parsed closure parameter shadows a step`() {
        assertParsedKind(JenkinsCallKind.LOCAL, "def f = { timeout -> timeout() }", "timeout")
    }

    @Test
    fun `object pipeline cannot establish declarative structure`() {
        assertParsedKind(JenkinsCallKind.STEP, "obj.pipeline { options { timeout() } }", "timeout")
    }

    @Test
    fun `nested option argument is not an option statement`() {
        assertParsedKind(
            JenkinsCallKind.STEP,
            "pipeline { options { timeout(time: timeout()) } }",
            "timeout",
            last = true,
        )
    }

    @Test
    fun `position rejects object and shadowed pipeline closures`() {
        val sources = listOf(
            "obj.pipeline { options { timeout() } }",
            "def pipeline(body) {}\npipeline { options { timeout() } }",
            "def pipeline = { Closure body -> body() }\npipeline { options { timeout() } }",
        )
        sources.forEach { source ->
            val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
            val uri = URI("file:///Jenkinsfile")
            val model = RecursiveAstVisitor(NodeRelationshipTracker())
            model.visitModule(parsed.module, uri)
            val call = model.getNodes(uri).filterIsInstance<MethodCallExpression>()
                .single { it.methodAsString == "timeout" }
            val context = JenkinsCallResolver.positionContext(
                call,
                JenkinsCallContext(model, uri, JenkinsSourceContext(source)),
                Position(call.lineNumber - 1, call.columnNumber - 1),
            )
            assertEquals(JenkinsRegion.EXECUTABLE_GROOVY, context.region, source)
        }
    }

    @Test
    fun `closure on object within directive arguments is executable`() {
        assertParsedKind(
            JenkinsCallKind.STEP,
            "pipeline { options { timeout(time: [1].collect { timeout() }) } }",
            "timeout",
            last = true,
        )
    }

    private fun assertParsedKind(
        expected: JenkinsCallKind,
        source: String,
        name: String,
        last: Boolean = false,
        varsNames: Set<String> = emptySet(),
    ) {
        val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
        val uri = URI("file:///Jenkinsfile")
        val model = RecursiveAstVisitor(NodeRelationshipTracker())
        model.visitModule(parsed.module, uri)
        val calls = model.getNodes(uri).filterIsInstance<MethodCallExpression>().filter { it.methodAsString == name }
        val call = if (last) calls.maxBy { it.columnNumber } else calls.single()
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(model, uri, JenkinsSourceContext(source)),
            metadata(),
            varsNames,
        )
        assertEquals(expected, result.kind, source)
    }

    @Test
    fun `library global wins over step metadata and has no step parameters`() {
        assertParsedKind(
            JenkinsCallKind.LIBRARY_GLOBAL,
            "timeout()",
            "timeout",
            varsNames = setOf("timeout"),
        )
    }

    @Test
    fun `AST belonging to another document cannot establish a Jenkins step`() {
        val source = "timeout()"
        val sourceUri = URI("file:///A/Jenkinsfile")
        val otherUri = URI("file:///B/Jenkinsfile")
        val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
        val model = RecursiveAstVisitor(NodeRelationshipTracker())
        model.visitModule(parsed.module, sourceUri)
        val call = model.getNodes(sourceUri).filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "timeout" }
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(model, otherUri, JenkinsSourceContext(source)),
            metadata(),
        )
        assertEquals(JenkinsCallKind.UNKNOWN, result.kind)
    }

    @Test
    fun `missing AST does not establish a Jenkins step`() {
        val call = MethodCallExpression(VariableExpression("this"), "timeout", ArgumentListExpression()).apply {
            lineNumber = 1
            columnNumber = 1
        }
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(null, URI("file:///Jenkinsfile"), JenkinsSourceContext("timeout()")),
            metadata(),
        )
        assertEquals(JenkinsCallKind.UNKNOWN, result.kind)
    }

    @Test
    fun `calls before and after a local declaration resolve in lexical order`() = runBlocking {
        val sources = listOf(
            "node { timeout(); def timeout = {}; timeout() }",
            "def helper() { timeout(); def timeout = {}; timeout() }",
        )
        for (source in sources) {
            val compilation = GroovyCompilationService()
            val uri = URI("file:///Jenkinsfile")
            try {
                compilation.compile(uri, source)
                val model = requireNotNull(compilation.getParseResult(uri)).astModel
                val calls = model.getNodes(uri).filterIsInstance<MethodCallExpression>().filter { call ->
                    call.methodAsString == "timeout" ||
                        (
                            call.methodAsString == "call" &&
                                (call.objectExpression as? VariableExpression)?.name == "timeout"
                            )
                }.sortedWith(compareBy({ it.lineNumber }, { it.columnNumber }))
                assertEquals(2, calls.size, source)
                val context = JenkinsCallContext(model, uri, JenkinsSourceContext(source))
                val kinds = calls.map { JenkinsCallResolver.resolve(it, context, metadata()).kind }
                assertEquals(listOf(JenkinsCallKind.STEP, JenkinsCallKind.LOCAL), kinds, source)
            } finally {
                compilation.shutdown()
            }
        }
    }

    @Test
    fun `static helper cannot dispatch a Jenkins step through the script instance`() {
        val source = "static def helper() { timeout() }"
        val parsed = UntrustedAstParser.parse(source, "Jenkinsfile") as UntrustedParseResult.Parsed
        assertEquals(true, parsed.module.methods.single { it.name == "helper" }.isStatic)
        assertParsedKind(JenkinsCallKind.LOCAL, source, "timeout")
        assertParsedKind(JenkinsCallKind.STEP, "def helper() { timeout() }", "timeout")
    }

    @Test
    fun `explicit steps receiver selects the step when a same-name library global exists`() {
        val globals = setOf("timeout")
        assertParsedKind(JenkinsCallKind.LIBRARY_GLOBAL, "timeout()", "timeout", varsNames = globals)
        assertParsedKind(JenkinsCallKind.STEP, "steps.timeout()", "timeout", varsNames = globals)
        assertParsedKind(JenkinsCallKind.LOCAL, "steps.timeout()", "timeout", varsNames = globals + "steps")
    }

    @Test
    fun `library global named steps shadows the dynamic steps receiver`() {
        assertParsedKind(JenkinsCallKind.LOCAL, "steps.timeout()", "timeout", varsNames = setOf("steps"))
    }

    @Test
    fun `this call in ordinary class does not become a pipeline step`() {
        val type = ClassNode("Helper", 1, ClassHelper.OBJECT_TYPE)
        val call = MethodCallExpression(VariableExpression("this"), "timeout", ArgumentListExpression()).apply {
            lineNumber = 1
            columnNumber = 1
        }
        val model = mockk<GroovyAstModel> {
            every { getNodes(any()) } returns emptyList()
            every { getUri(call) } returns URI("file:///Helper.groovy")
            every { getParent(call) } returns type
            every { getParent(type) } returns null
        }
        val result = JenkinsCallResolver.resolve(
            call,
            JenkinsCallContext(model, URI("file:///Helper.groovy"), JenkinsSourceContext("this.timeout()")),
            metadata(),
        )
        assertEquals(JenkinsCallKind.LOCAL, result.kind)
    }

    private fun assertKind(expected: JenkinsCallKind, source: String, name: String) {
        assertParsedKind(expected, source, name)
    }

    private fun metadata(): MergedJenkinsMetadata {
        val step = MergedStepMetadata(
            name = "timeout",
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = emptyMap(),
            extractedDocumentation = null,
            returnType = null,
            plugin = null,
            enrichedDescription = null,
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = null,
        )
        val option = MergedDeclarativeOption("timeout", "pipeline", emptyMap(), "option documentation")
        return MergedJenkinsMetadata(
            "2",
            mapOf("timeout" to step),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            mapOf("timeout" to option),
        )
    }
}
