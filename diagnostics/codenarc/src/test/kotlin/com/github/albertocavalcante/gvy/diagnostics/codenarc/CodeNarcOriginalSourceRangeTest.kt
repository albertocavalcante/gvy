package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.CompilationAccessor
import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeNarcOriginalSourceRangeTest {
    companion object {
        @JvmStatic
        fun stringCases(): List<Arguments> = listOf(
            Arguments.of("    ", "Hello, World!"),
            Arguments.of("\t\t", "Hello, World!"),
            Arguments.of("    ", "Hello, 😀 World!"),
        )

        @JvmStatic
        fun astCases(): List<Arguments> = listOf(
            Arguments.of("    class badname { def run() { 1 } }\n", "ClassName", "badname", 0),
            Arguments.of("class Demo {\n    def BadName() { 1 }\n}\n", "MethodName", "BadName", 1),
            Arguments.of("class Demo {\n    private String BAD = 'hello'\n}\n", "FieldName", "BAD", 1),
            Arguments.of(
                "class Demo {\n    def run() {\n        def abandoned = 1\n    }\n}\n",
                "UnusedVariable",
                "abandoned",
                2,
            ),
            Arguments.of(
                "    import java.util.concurrent.TimeUnit\nclass Demo { def run() { 1 } }\n",
                "UnusedImport",
                "TimeUnit",
                0,
            ),
        )
    }

    private val context = object : WorkspaceContext {
        override val root: Path? = null
        override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
            override val isEnabled = true
            override val propertiesFile: String? = null
            override val autoDetectConfig = false
        }
    }
    private val resolver = object : RulesetResolver {
        override fun resolve(context: WorkspaceContext): RulesetConfiguration = RulesetConfiguration(
            rulesetContent = "ruleset { ruleset('rulesets/unnecessary.xml') }",
            propertiesFile = null,
            source = "source-range-regression",
        )
    }

    @ParameterizedTest
    @MethodSource("stringCases")
    fun `real CodeNarc string warnings cover original source after indentation and Unicode`(
        prefix: String,
        text: String,
    ) {
        val literal = "\"$text\""
        val line = prefix + "println $literal"
        val source = "class Greeter {\n    def run() {\n$line\n    }\n}\n"
        val diagnostics = realProvider().analyzeAndGetDiagnostics(source, "Greeter.groovy")
        val diagnostic = diagnostics.single { it.code.left == "UnnecessaryGString" }
        val start = line.indexOf('"')
        assertEquals(Range(Position(2, start), Position(2, line.length)), diagnostic.range)
        assertEquals(literal, line.substring(diagnostic.range.start.character, diagnostic.range.end.character))
        assertEquals(DiagnosticSeverity.Information, diagnostic.severity)
        assertEquals("CodeNarc", diagnostic.source)
        assertTrue(diagnostic.message.contains(text))
        assertEquals(prefix + "println $literal", source.lines()[2])
    }

    @Test
    fun `real CodeNarc return warning covers the keyword rather than indentation with CRLF`() {
        val line = "        return 'hello'"
        val source = "class Greeter {\r\n    String greet() {\r\n$line\r\n    }\r\n}\r\n"
        val diagnostic = realProvider().analyzeAndGetDiagnostics(source, "Greeter.groovy")
            .single { it.code.left == "UnnecessaryReturnKeyword" }
        assertEquals(Range(Position(2, 8), Position(2, 14)), diagnostic.range)
        assertEquals("return", line.substring(diagnostic.range.start.character, diagnostic.range.end.character))
        assertEquals(DiagnosticSeverity.Information, diagnostic.severity)
    }

    @Test
    fun `trailing whitespace uses original columns even when CodeNarc strips indentation`() {
        val line = "    println 'hello'   "
        val violation = violation("TrailingWhitespace", line.trimStart(), "Line ends with whitespace characters")
        val diagnostic = stubProvider(violation).analyzeAndGetDiagnostics(line, "Hello.groovy").single()
        assertEquals(Range(Position(0, line.length - 3), Position(0, line.length)), diagnostic.range)
        assertEquals("   ", line.substring(diagnostic.range.start.character, diagnostic.range.end.character))
        assertEquals("TrailingWhitespace", diagnostic.code.left)
    }

    @Test
    fun `indentation warnings underline actual whitespace when snippet has none`() {
        val line = "   println 'hello'"
        val violation = violation("Indentation", line.trimStart(), "Incorrect indentation")
        val diagnostic = stubProvider(violation).analyzeAndGetDiagnostics(line, "Hello.groovy").single()
        assertEquals(Range(Position(0, 0), Position(0, 3)), diagnostic.range)
    }

    @Test
    fun `unindented source remains correctly positioned`() {
        val line = "println \"Hello, World!\""
        val violation =
            violation("UnnecessaryGString", line, "The String 'Hello, World!' can be wrapped in single quotes")
        val diagnostic = stubProvider(violation).analyzeAndGetDiagnostics(line, "Hello.groovy").single()
        assertEquals(Range(Position(0, 8), Position(0, line.length)), diagnostic.range)
    }

    @Test
    fun `legacy helper still accepts standalone CodeNarc snippets`() {
        val line = "println \"Hello, World!\""
        val violation =
            violation("UnnecessaryGString", line, "The String 'Hello, World!' can be wrapped in single quotes")
        assertEquals(8 to line.length, RuleRangeCalculator.calculateRange(violation, "unrelated legacy fallback"))
    }

    @ParameterizedTest
    @MethodSource("astCases")
    fun `AST-backed CodeNarc diagnostics use the original line too`(
        source: String,
        rule: String,
        identifier: String,
        line: Int,
    ) {
        val unit = CompilationUnit().apply {
            addSource("CodeNarcRanges.groovy", source)
            compile(Phases.CONVERSION)
        }
        val uri = URI.create("file:///CodeNarcRanges.groovy")
        val accessor = CompilationAccessor { requested ->
            assertEquals(uri, requested)
            unit.ast.modules.single()
        }
        val namingResolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = RulesetConfiguration(
                rulesetContent = """
                    ruleset {
                        ruleset('rulesets/naming.xml')
                        ruleset('rulesets/unused.xml')
                        ruleset('rulesets/imports.xml')
                    }
                """.trimIndent(),
                propertiesFile = null,
                source = "ast-source-range-regression",
            )
        }
        val provider = CodeNarcDiagnosticProvider(context, accessor, namingResolver, DefaultCodeAnalyzer())
        val diagnostic = provider.analyzeAndGetDiagnostics(source, "CodeNarcRanges.groovy", uri)
            .single { it.code.left == rule }
        val originalLine = source.lines()[line]
        val start = originalLine.indexOf(identifier)
        assertTrue(start >= 0)
        assertEquals(Range(Position(line, start), Position(line, start + identifier.length)), diagnostic.range)
        assertEquals(
            identifier,
            originalLine.substring(diagnostic.range.start.character, diagnostic.range.end.character),
        )
    }

    private fun realProvider(): CodeNarcDiagnosticProvider = CodeNarcDiagnosticProvider(
        workspaceContext = context,
        rulesetResolver = resolver,
        codeAnalyzer = DefaultCodeAnalyzer(),
    )

    private fun violation(name: String, snippet: String, message: String): Violation {
        val rule = mockk<Rule>()
        every { rule.name } returns name
        every { rule.priority } returns 3
        return mockk<Violation>().also {
            every { it.rule } returns rule
            every { it.lineNumber } returns 1
            every { it.sourceLine } returns snippet
            every { it.message } returns message
        }
    }

    private fun stubProvider(violation: Violation): CodeNarcDiagnosticProvider {
        val results = mockk<Results>()
        every { results.children } returns emptyList()
        every { results.violations } returns listOf(violation)
        val analyzer = object : CodeAnalyzer {
            override fun analyze(
                sourceCode: String,
                fileName: String,
                rulesetContent: String,
                propertiesFile: String?,
            ): Results = results
        }
        return CodeNarcDiagnosticProvider(context, rulesetResolver = resolver, codeAnalyzer = analyzer)
    }
}
