package com.github.albertocavalcante.groovylsp.providers.semantictokens

import org.eclipse.lsp4j.SemanticTokenTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticTokenProviderTest {

    private val provider = SemanticTokenProvider()

    @Test
    fun `should create proper semantic tokens legend`() {
        val legend = SemanticTokenProvider.createLegend()

        assertEquals(1, legend.tokenTypes.size)
        assertEquals(SemanticTokenTypes.Comment, legend.tokenTypes[0])

        assertEquals(5, legend.tokenModifiers.size)
        assertEquals("todo", legend.tokenModifiers[0])
        assertEquals("fixme", legend.tokenModifiers[1])
        assertEquals("warning", legend.tokenModifiers[2])
        assertEquals("error", legend.tokenModifiers[3])
        assertEquals("note", legend.tokenModifiers[4])
    }

    @Test
    fun `should generate semantic tokens for single-line TODO comments`() {
        val sourceCode = """
            class TestClass {
                // TODO: implement this method
                def method() {}

                // FIXME: fix the bug here
                def anotherMethod() {}
            }
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 2 tokens: TODO and FIXME (BUG in natural language should be ignored)
        assertEquals(10, tokens.data.size) // 2 tokens * 5 values per token

        // Verify we have the correct token types (don't assert exact positions)
        val modifiers = mutableListOf<Int>()
        for (i in 4 until tokens.data.size step 5) {
            modifiers.add(tokens.data[i])
        }

        // Check that we have TODO and FIXME tokens
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_TODO))
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_FIXME))
        assertFalse(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_ERROR)) // Should not find BUG
    }

    @Test
    fun `should generate semantic tokens for various comment patterns`() {
        val sourceCode = """
            // TODO: basic todo
            // FIXME: basic fixme
            // XXX: dangerous code
            // HACK: temporary workaround
            // NOTE: important note
            // BUG: known bug
            // OPTIMIZE: performance issue
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 7 tokens * 5 values per token = 35 values
        assertEquals(35, tokens.data.size)

        // Check modifier assignments for different keywords
        val modifiers = mutableListOf<Int>()
        for (i in 4 until tokens.data.size step 5) {
            modifiers.add(tokens.data[i])
        }

        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_TODO)) // TODO
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_FIXME)) // FIXME
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_WARNING)) // XXX, HACK
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_ERROR)) // BUG
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_NOTE)) // NOTE, OPTIMIZE
    }

    @Test
    fun `should handle case-insensitive TODO patterns`() {
        val sourceCode = """
            // todo: lowercase
            // Todo: mixed case
            // TODO: uppercase
            // fixme: lowercase fixme
            // FIXME: uppercase fixme
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 5 tokens
        assertEquals(25, tokens.data.size) // 5 tokens * 5 values per token

        // All should be recognized as valid tokens
        for (i in 0 until tokens.data.size step 5) {
            val tokenType = tokens.data[i + 3]
            assertEquals(SemanticTokenProvider.TOKEN_TYPE_COMMENT, tokenType)
        }
    }

    @Test
    fun `should handle TODO patterns with and without colons`() {
        val sourceCode = """
            // TODO implement without colon
            // TODO: implement with colon
            // FIXME fix without colon
            // FIXME: fix with colon
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 4 tokens
        assertEquals(20, tokens.data.size) // 4 tokens * 5 values per token

        // Should find 4 tokens with correct lengths (keyword length, not including colon)
        assertEquals(20, tokens.data.size) // 4 tokens * 5 values per token

        // Verify we have expected token modifiers
        val modifiers = mutableListOf<Int>()
        for (i in 4 until tokens.data.size step 5) {
            modifiers.add(tokens.data[i])
        }

        // Should have both TODO and FIXME tokens
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_TODO))
        assertTrue(modifiers.contains(1 shl SemanticTokenProvider.MODIFIER_FIXME))
    }

    @Test
    fun `should generate range tokens correctly`() {
        val sourceCode = """
            class TestClass {
                // TODO: implement this method
                def method() {}

                // FIXME: fix the bug here
                def anotherMethod() {}

                // NOTE: another comment
            }
        """.trimIndent()

        // Test range tokens for lines 1-5 (should include TODO and FIXME but not NOTE)
        val tokens = provider.generateSemanticTokensRange(sourceCode, 1, 6, "test.groovy")

        // Should have 2 tokens (TODO and FIXME)
        assertEquals(10, tokens.data.size)
    }

    @Test
    fun `should return empty tokens for code without TODO comments`() {
        val sourceCode = """
            class TestClass {
                def method() {
                    return "no comments here"
                }
            }
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        assertEquals(0, tokens.data.size)
    }

    @Test
    fun `should handle empty source code`() {
        val tokens = provider.generateSemanticTokens("", "test.groovy")
        assertEquals(0, tokens.data.size)
    }

    @Test
    fun `should detect if semantic tokens are needed`() {
        val codeWithTodos = """
            // TODO: implement this
            def method() {}
        """.trimIndent()

        val codeWithoutTodos = """
            def method() {
                return "no todos here"
            }
        """.trimIndent()

        assertTrue(provider.hasSemanticTokens(codeWithTodos))
        assertFalse(provider.hasSemanticTokens(codeWithoutTodos))
    }

    @Test
    fun `should handle multiple TODO patterns on same line`() {
        val sourceCode = """
            // TODO: first part FIXME: second part
            def method() {}
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 1 token (only the TODO: at the beginning, not FIXME in natural language)
        assertEquals(5, tokens.data.size) // 1 token * 5 values per token

        // Token should be TODO modifier
        assertEquals(1 shl SemanticTokenProvider.MODIFIER_TODO, tokens.data[4])
    }

    @Test
    fun `should handle nested comment structures`() {
        val sourceCode = """
            // Regular comment
            // TODO: implement feature // Another comment after
            def method() {}
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        // Should have 1 token (only TODO should be highlighted)
        assertEquals(5, tokens.data.size)

        val tokenLine = tokens.data[0]
        val tokenChar = tokens.data[1]
        val tokenLength = tokens.data[2]

        assertEquals(1, tokenLine) // Second line
        assertEquals(3, tokenChar) // Position of "TODO" (after // )
        assertEquals(4, tokenLength) // Length of "TODO"
    }

    @Test
    fun `should handle delta encoding correctly with sorting`() {
        val sourceCode = """
            // FIXME: second in source order
            // TODO: first in source order
        """.trimIndent()

        val tokens = provider.generateSemanticTokens(sourceCode, "test.groovy")

        assertEquals(10, tokens.data.size)

        // First token in encoded output should be FIXME (appears first in source)
        assertEquals(0, tokens.data[0]) // line 0
        assertEquals(3, tokens.data[1]) // char 3 (after // )
        assertEquals(5, tokens.data[2]) // length 5 ("FIXME")

        // Second token should be TODO (delta from FIXME)
        assertEquals(1, tokens.data[5]) // line delta 1
        assertEquals(3, tokens.data[6]) // char 3 (absolute since different line, after // )
        assertEquals(4, tokens.data[7]) // length 4 ("TODO")
    }
}
