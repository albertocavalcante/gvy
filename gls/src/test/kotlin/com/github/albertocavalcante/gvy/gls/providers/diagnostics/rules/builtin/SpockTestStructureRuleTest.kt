package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import com.github.albertocavalcante.nativeapi.ParseRequest
import groovy.lang.GroovyClassLoader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.spockframework.runtime.model.FeatureMetadata
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpockTestStructureRuleTest {
    private val rule = SpockTestStructureRule()
    private val uri = URI.create("file:///MinimalSpec.groovy")

    @Test
    fun `given-only feature is accepted by Spock and receives no template diagnostic`(): Unit = runBlocking {
        val content = spec("def \"given only\"() { given: def value = 1 }")
        assertCompiledFeatures(content, setOf("given only"))
        assertNoReport(content)
    }

    @Test
    fun `cleanup-only feature is accepted by Spock and receives no template diagnostic`(): Unit = runBlocking {
        val content = spec("def \"cleanup only\"() { cleanup: def value = 1 }")
        assertCompiledFeatures(content, setOf("cleanup only"))
        assertNoReport(content)
    }

    @Test
    fun `where-only feature is accepted by Spock and receives no template diagnostic`(): Unit = runBlocking {
        val content =
            spec(
                """
            def "where only"() {
                where:
                value | expected
                1     | 1
            }
                """.trimIndent(),
            )
        assertCompiledFeatures(content, setOf("where only"))
        assertNoReport(content)
    }

    @Test
    fun `quoted and test-prefixed helpers are not diagnosed as features`(): Unit = runBlocking {
        val helpers = """
            def "quoted helper"() { 42 }
            def testHelper() { 42 }
        """.trimIndent()
        val content = spec(helpers)
        assertCompiledFeatures(content, emptySet())
        assertNoReport(content)
        val adjacent = spec(helpers + "\n" + "def \"real feature\"() { expect: true }")
        assertCompiledFeatures(adjacent, setOf("real feature"))
        assertNoReport(adjacent)
    }

    @Test
    fun `valid long feature is not limited by a twenty-line lookahead`(): Unit = runBlocking {
        val setup = List(25) { "def value$it = $it" }.joinToString("\n")
        val content =
            spec(
                """
            def "long feature"() {
                given:
                $setup
                when:
                def result = value0 + 1
                then:
                result == 1
            }
                """.trimIndent(),
            )
        assertCompiledFeatures(content, setOf("long feature"))
        assertNoReport(content)
    }

    @Test
    fun `block-like strings and comments do not turn helpers into incomplete features`(): Unit = runBlocking {
        val content =
            spec(
                """
            def "quoted helper"() {
                def description = 'then: expect: where:'
                /* given: when: then: */
                description
            }
                """.trimIndent(),
            )
        assertCompiledFeatures(content, emptySet())
        assertNoReport(content)
    }

    @Test
    fun `ordinary Groovy labels and Test filenames do not establish a specification`(): Unit = runBlocking {
        val content = """
            class OrdinaryTest {
                def testHelper() {
                    given: def value = 1
                    value
                }
            }
        """.trimIndent()
        assertNoReport(content, URI.create("file:///OrdinaryTest.groovy"))
    }

    @Test
    fun `missing AST never enables a text-only diagnostic`(): Unit = runBlocking {
        val content = spec("def \"quoted helper\"() { 42 }")
        assertTrue(rule.analyze(uri, content, context()).isEmpty())
    }

    @Test
    fun `parser syntax diagnostics remain without a speculative structural duplicate`(): Unit = runBlocking {
        val content = spec("def \"unfinished\"() { then: value == }")
        val parsed = GroovyParserFacade().parse(ParseRequest(uri, content))
        assertTrue(!parsed.isSuccessful)
        assertTrue(parsed.diagnostics.isNotEmpty())
        assertTrue(rule.analyze(uri, content, context(parsed.ast, errors = true)).isEmpty())
    }

    @Test
    fun `registration and configuration metadata remain compatible`() {
        assertEquals("spock-test-structure", rule.id)
        assertEquals(DiagnosticSeverity.Information, rule.defaultSeverity)
        assertEquals(DiagnosticAnalysisType.HEURISTIC, rule.analysisType)
        assertTrue(rule.enabledByDefault)
        assertTrue(BuiltinRules.getDefaultRules().any { it.id == rule.id })
        assertTrue(BuiltinRules.getRulesForContext(BuiltinRules.RuleContext.SPOCK).any { it.id == rule.id })
    }

    @Test
    fun `other diagnostic rules continue to report`(): Unit = runBlocking {
        val content = "println('debug')"
        val reports = PrintlnDebugRule().analyze(uri, content, context())
        assertEquals(1, reports.size)
        assertTrue(rule.analyze(uri, content, context()).isEmpty())
    }

    private suspend fun assertNoReport(content: String, documentUri: URI = uri) {
        val parsed = GroovyParserFacade().parse(ParseRequest(documentUri, content))
        assertNotNull(parsed.ast)
        assertTrue(parsed.isSuccessful)
        assertTrue(rule.analyze(documentUri, content, context(parsed.ast)).isEmpty())
    }

    private fun assertCompiledFeatures(content: String, expected: Set<String>) {
        // Unlike the LSP parser, this controlled oracle must run the real Spock transform.
        GroovyClassLoader(javaClass.classLoader).use { loader ->
            val compiled = loader.parseClass(content, "MinimalSpec.groovy")
            val features = compiled.declaredMethods.mapNotNull {
                it.getAnnotation(FeatureMetadata::class.java)?.name
            }.toSet()
            assertEquals(expected, features)
        }
    }

    private fun spec(methods: String): String = "class MinimalSpec extends spock.lang.Specification {\n$methods\n}"

    private fun context(ast: Any? = null, errors: Boolean = false): RuleContext = mockk {
        every { hasErrors() } returns errors
        every { getAst() } returns ast
    }
}
