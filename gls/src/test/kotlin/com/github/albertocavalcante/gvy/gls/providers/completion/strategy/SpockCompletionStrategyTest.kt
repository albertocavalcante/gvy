// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionContext
import com.github.albertocavalcante.gvy.semantics.native.SymbolCompletionContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.Test
import java.net.URI

class SpockCompletionStrategyTest {

    private val strategy = SpockCompletionStrategy()

    @Test
    fun `compact features on one line keep data completions separate`() {
        val content = "class Example extends spock.lang.Specification { " +
            "def first(){ expect: a > 0; where: a << [1] }; " +
            "def second(){ expect: b > 0; where: b << [2] } }"
        val context = createSpockContext(content, 0, content.lastIndexOf("expect:") + "expect: ".length)
        val module = com.github.albertocavalcante.groovyparser.GroovyParserFacade().parse(
            com.github.albertocavalcante.nativeapi.ParseRequest(
                URI.create("file:///Example.groovy"),
                content,
                compilePhase = org.codehaus.groovy.control.Phases.CONVERSION,
            ),
        ).ast
        every { context.baseContext.moduleNode } returns module
        val result = runBlocking { strategy.complete(context) }
        result.fold(
            ifLeft = { throw AssertionError("Expected data completion: $it") },
            ifRight = { items -> assertThat(items.map { it.label }).containsExactly("b") },
        )
    }

    @Test
    fun `completes data variables declared later in the same feature`() {
        val content = """
            import spock.lang.Specification
            class Example extends Specification {
                def "data feature"() {
                    expect:
                    val
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        val context = createSpockContext(content, 4, 11)
        val module = com.github.albertocavalcante.groovyparser.GroovyParserFacade().parse(
            com.github.albertocavalcante.nativeapi.ParseRequest(
                URI.create("file:///Example.groovy"),
                content,
                compilePhase = org.codehaus.groovy.control.Phases.CONVERSION,
            ),
        ).ast
        every { context.baseContext.moduleNode } returns module
        val result = runBlocking { strategy.complete(context) }
        result.fold(
            ifLeft = { throw AssertionError("Expected data completion: $it") },
            ifRight = { items -> assertThat(items.map { it.label }).contains("value") },
        )
    }

    @Test
    fun `suggests block labels in Spock test methods`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {

                }
            }
        """.trimIndent()

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 8, // At indentation before closing brace
        )

        val result = runBlocking { strategy.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).isNotEmpty
                val labels = items.map { it.label }
                assertThat(labels).containsExactlyInAnyOrder(
                    "given:",
                    "setup:",
                    "when:",
                    "then:",
                    "expect:",
                    "where:",
                    "cleanup:",
                    "and:",
                )

                // Verify all items have correct properties
                items.forEach { item ->
                    assertThat(item.kind).isEqualTo(CompletionItemKind.Keyword)
                    assertThat(item.detail).isEqualTo("Spock block label")
                    assertThat(item.documentation).isNotNull
                    assertThat(item.sortText).startsWith("0-")
                }
            },
        )
    }

    @Test
    fun `no completions when cursor is mid-expression`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {
                    def x = 5
                }
            }
        """.trimIndent()

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 14, // After "def x = 5"
        )

        val result = runBlocking { strategy.complete(context) }

        // Strategy should not apply mid-expression
        assertThat(result.isLeft()).isTrue()
    }

    @Test
    fun `no completions in non-Spock class`() {
        val content = """
            class MyClass {
                def myMethod() {

                }
            }
        """.trimIndent()

        val context = createNonSpockContext(
            content = content,
            line = 2,
            character = 8,
        )

        val result = runBlocking { strategy.complete(context) }

        // Strategy should not apply to non-Spock classes
        assertThat(result.isLeft()).isTrue()
    }

    @Test
    fun `completions have correct sort order`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {

                }
            }
        """.trimIndent()

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 8,
        )

        val result = runBlocking { strategy.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // All Spock block labels should sort with "0-" prefix
                items.forEach { item ->
                    assertThat(item.sortText).startsWith("0-")
                    assertThat(item.sortText).isEqualTo("0-${item.label}")
                }
            },
        )
    }

    @Test
    fun `no completions when context type is detected`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {

                }
            }
        """.trimIndent()

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 8,
            contextType = mockk(), // Simulate some context detected
        )

        val result = runBlocking { strategy.complete(context) }

        // Strategy should not apply when another context is detected
        assertThat(result.isLeft()).isTrue()
    }

    @Test
    fun `no completions when cursor is in comment`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {
                    // comment
                }
            }
        """.trimIndent()

        val tokenIndex = mockk<com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex>()
        every { tokenIndex.isInCommentOrString(any()) } returns true

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 12, // Inside comment
            tokenIndex = tokenIndex,
        )

        val result = runBlocking { strategy.complete(context) }

        // Strategy should not apply in comments
        assertThat(result.isLeft()).isTrue()
    }

    @Test
    fun `no completions when cursor is in string`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {
                    def x = "some string"
                }
            }
        """.trimIndent()

        val tokenIndex = mockk<com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex>()
        every { tokenIndex.isInCommentOrString(any()) } returns true

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 22, // Inside string literal
            tokenIndex = tokenIndex,
        )

        val result = runBlocking { strategy.complete(context) }

        // Strategy should not apply in strings
        assertThat(result.isLeft()).isTrue()
    }

    @Test
    fun `block labels have correct documentation`() {
        val content = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "test method"() {

                }
            }
        """.trimIndent()

        val context = createSpockContext(
            content = content,
            line = 4,
            character = 8,
        )

        val result = runBlocking { strategy.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                val givenItem = items.find { it.label == "given:" }
                assertThat(givenItem).isNotNull
                assertThat(givenItem?.documentation?.left).isEqualTo("Spock setup block")

                val whenItem = items.find { it.label == "when:" }
                assertThat(whenItem).isNotNull
                assertThat(whenItem?.documentation?.left).isEqualTo("Spock action block")

                val thenItem = items.find { it.label == "then:" }
                assertThat(thenItem).isNotNull
                assertThat(thenItem?.documentation?.left).isEqualTo("Spock assertion block")

                val expectItem = items.find { it.label == "expect:" }
                assertThat(expectItem).isNotNull
                assertThat(expectItem?.documentation?.left).isEqualTo("Spock combined when/then block")

                val whereItem = items.find { it.label == "where:" }
                assertThat(whereItem).isNotNull
                assertThat(whereItem?.documentation?.left).isEqualTo("Spock data-driven block")
            },
        )
    }

    // Activation from the AST source context (#974): confident verdicts decide, UNCERTAIN keeps the heuristic.

    private fun parsed(content: String) = com.github.albertocavalcante.groovyparser.GroovyParserFacade().parse(
        com.github.albertocavalcante.nativeapi.ParseRequest(
            URI.create("file:///test/Example.groovy"),
            content,
            compilePhase = org.codehaus.groovy.control.Phases.CONVERSION,
        ),
    ).ast

    private fun completeAt(content: String, line: Int): List<String>? {
        val character = content.lines()[line].length
        val context = createSpockContext(content, line, character)
        every { context.baseContext.moduleNode } returns parsed(content)
        return runBlocking { strategy.complete(context) }
            .fold(ifLeft = { null }, ifRight = { items -> items.map { it.label } })
    }

    @Test
    fun `an ordinary class in a file importing Spock gets no block labels`() {
        val content = """
            import spock.lang.Specification
            class Helper {
                def run() {
                    given: 1

                }
            }
        """.trimIndent()

        assertThat(completeAt(content, 4)).isNull()
    }

    @Test
    fun `a helper next to a spec gets no block labels while the spec keeps them`() {
        val content = """
            import spock.lang.Specification
            class FooSpec extends Specification {
                def "feature"() {

                }
            }
            class Helper {
                def run() {

                }
            }
        """.trimIndent()

        assertThat(completeAt(content, 3)).contains("given:", "expect:")
        assertThat(completeAt(content, 8)).isNull()
    }

    @Test
    fun `an aliased Specification import is a spec for data variables too`() {
        val content = """
            import spock.lang.Specification as Spec
            class FooSpec extends Spec {
                def "feature"() {
                    expect:
                    val
                    where:
                    value << [1]
                }
            }
        """.trimIndent()

        assertThat(completeAt(content, 4)).contains("value")
    }

    @Test
    fun `uncertain classes keep today's heuristic`() {
        val externalBase = """
            class FooSpec extends BaseSpec {
                def "feature"() {
                    given: 1

                }
            }
        """.trimIndent()
        assertThat(completeAt(externalBase, 3)).contains("given:")

        val starOnly = """
            import spock.lang.*
            class FooSpec extends Specification {
                def "feature"() {

                }
            }
        """.trimIndent()
        assertThat(completeAt(starOnly, 3)).contains("given:")
    }

    // Helper methods to create test contexts

    private fun createSpockContext(
        content: String,
        line: Int,
        character: Int,
        contextType: com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.ContextType? = null,
        tokenIndex: com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex? = null,
    ): CompletionStrategyContext {
        val module = createSpockModule()
        val baseContext = mockk<CompletionContext>(relaxed = true)
        every { baseContext.uri } returns URI.create("file:///test/MySpec.groovy")
        every { baseContext.content } returns content
        every { baseContext.line } returns line
        every { baseContext.character } returns character
        every { baseContext.moduleNode } returns module
        every { baseContext.tokenIndex } returns tokenIndex
        every { baseContext.astModel } returns mockk(relaxed = true)

        return CompletionStrategyContext(
            baseContext = baseContext,
            symbolContext = SymbolCompletionContext(
                classes = emptyList(),
                methods = emptyList(),
                fields = emptyList(),
                imports = emptyList(),
                variables = emptyList(),
                currentClass = null,
            ),
            nodeAtCursor = null,
            contextType = contextType,
            mode = GroovyMode.GROOVY,
            isJenkinsFile = false,
            jenkinsMetadata = null,
            jenkinsBlockContext = null,
        )
    }

    private fun createNonSpockContext(content: String, line: Int, character: Int): CompletionStrategyContext {
        val module = createNonSpockModule()
        val baseContext = mockk<CompletionContext>(relaxed = true)
        every { baseContext.uri } returns URI.create("file:///test/MyClass.groovy")
        every { baseContext.content } returns content
        every { baseContext.line } returns line
        every { baseContext.character } returns character
        every { baseContext.moduleNode } returns module
        every { baseContext.tokenIndex } returns null
        every { baseContext.astModel } returns mockk(relaxed = true)

        return CompletionStrategyContext(
            baseContext = baseContext,
            symbolContext = SymbolCompletionContext(
                classes = emptyList(),
                methods = emptyList(),
                fields = emptyList(),
                imports = emptyList(),
                variables = emptyList(),
                currentClass = null,
            ),
            nodeAtCursor = null,
            contextType = null,
            mode = GroovyMode.GROOVY,
            isJenkinsFile = false,
            jenkinsMetadata = null,
            jenkinsBlockContext = null,
        )
    }

    private fun createSpockModule(): ModuleNode {
        val sourceUnit = mockk<org.codehaus.groovy.control.SourceUnit>(relaxed = true)
        val module = ModuleNode(sourceUnit)

        // Create a Spock Specification class
        val specClass = ClassNode(
            "MySpec",
            0,
            ClassHelper.OBJECT_TYPE,
            emptyArray(),
            emptyArray(),
        )

        // Set superclass to Specification
        val specificationClass = ClassNode("spock.lang.Specification", 0, ClassHelper.OBJECT_TYPE)
        specClass.superClass = specificationClass

        module.classes.add(specClass)

        // Add Spock import
        val importNode = org.codehaus.groovy.ast.ImportNode(
            ClassHelper.make("spock.lang.Specification"),
            "Specification",
        )
        module.imports.add(importNode)

        return module
    }

    private fun createNonSpockModule(): ModuleNode {
        val sourceUnit = mockk<org.codehaus.groovy.control.SourceUnit>(relaxed = true)
        val module = ModuleNode(sourceUnit)

        // Create a regular class (not extending Specification)
        val regularClass = ClassNode(
            "MyClass",
            0,
            ClassHelper.OBJECT_TYPE,
            emptyArray(),
            emptyArray(),
        )

        module.classes.add(regularClass)

        return module
    }
}
