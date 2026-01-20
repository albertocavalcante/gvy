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
