package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SemanticTokensEncoder].
 *
 * Tests the semantic token encoding logic extracted from GroovyTextDocumentService.
 */
class SemanticTokensEncoderTest {

    // Helper to create test tokens
    private fun token(line: Int, startChar: Int, length: Int, tokenType: Int = 0, tokenModifiers: Int = 0) =
        JenkinsSemanticTokenProvider.SemanticToken(
            line = line,
            startChar = startChar,
            length = length,
            tokenType = tokenType,
            tokenModifiers = tokenModifiers,
        )

    private fun groovyToken(line: Int, startChar: Int, length: Int, tokenType: Int = 0, tokenModifiers: Int = 0) =
        GroovySemanticTokenProvider.SemanticToken(
            line = line,
            startChar = startChar,
            length = length,
            tokenType = tokenType,
            tokenModifiers = tokenModifiers,
        )

    // ============================================
    // encode() tests
    // ============================================

    @Test
    fun `encode returns empty list for empty tokens`() {
        val result = SemanticTokensEncoder.encode(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `encode returns 5 integers per token`() {
        val tokens = listOf(token(line = 0, startChar = 0, length = 5))
        val result = SemanticTokensEncoder.encode(tokens)
        assertEquals(5, result.size)
    }

    @Test
    fun `encode calculates delta line correctly for first token`() {
        val tokens = listOf(token(line = 2, startChar = 5, length = 3, tokenType = 1, tokenModifiers = 0))
        val result = SemanticTokensEncoder.encode(tokens)

        // First token: deltaLine = 2, deltaChar = 5 (absolute since first on line)
        assertEquals(2, result[0], "deltaLine should be 2")
        assertEquals(5, result[1], "deltaChar should be 5 (absolute for first token)")
        assertEquals(3, result[2], "length should be 3")
        assertEquals(1, result[3], "tokenType should be 1")
        assertEquals(0, result[4], "tokenModifiers should be 0")
    }

    @Test
    fun `encode calculates delta char on same line relative to previous token`() {
        val tokens = listOf(
            token(line = 0, startChar = 0, length = 5, tokenType = 1),
            token(line = 0, startChar = 10, length = 3, tokenType = 2),
        )
        val result = SemanticTokensEncoder.encode(tokens)

        // First token: deltaLine=0, deltaChar=0
        assertEquals(0, result[0])
        assertEquals(0, result[1])

        // Second token on same line: deltaLine=0, deltaChar=10-0=10
        assertEquals(0, result[5], "deltaLine should be 0 (same line)")
        assertEquals(10, result[6], "deltaChar should be 10 (relative to prev token)")
    }

    @Test
    fun `encode resets delta char on new line to absolute position`() {
        val tokens = listOf(
            token(line = 0, startChar = 5, length = 3),
            token(line = 2, startChar = 8, length = 4),
        )
        val result = SemanticTokensEncoder.encode(tokens)

        // Second token on new line: deltaLine=2, deltaChar=8 (absolute reset)
        assertEquals(2, result[5], "deltaLine should be 2")
        assertEquals(8, result[6], "deltaChar should be 8 (absolute on new line)")
    }

    @Test
    fun `encode sorts tokens by line then character`() {
        // Input tokens in wrong order
        val tokens = listOf(
            token(line = 1, startChar = 5, length = 3, tokenType = 2),
            token(line = 0, startChar = 0, length = 5, tokenType = 1),
            token(line = 1, startChar = 0, length = 2, tokenType = 3),
        )
        val result = SemanticTokensEncoder.encode(tokens)

        // Should encode in sorted order: (0,0), (1,0), (1,5)
        // Token 1: line=0, char=0 -> deltaLine=0, deltaChar=0, type=1
        assertEquals(0, result[0])
        assertEquals(0, result[1])
        assertEquals(1, result[3], "First encoded token should be type 1")

        // Token 2: line=1, char=0 -> deltaLine=1, deltaChar=0, type=3
        assertEquals(1, result[5])
        assertEquals(0, result[6])
        assertEquals(3, result[8], "Second encoded token should be type 3")

        // Token 3: line=1, char=5 -> deltaLine=0, deltaChar=5, type=2
        assertEquals(0, result[10])
        assertEquals(5, result[11])
        assertEquals(2, result[13], "Third encoded token should be type 2")
    }

    @Test
    fun `encode preserves token type and modifiers`() {
        val tokens = listOf(
            token(line = 0, startChar = 0, length = 5, tokenType = 7, tokenModifiers = 3),
        )
        val result = SemanticTokensEncoder.encode(tokens)

        assertEquals(7, result[3], "tokenType should be preserved")
        assertEquals(3, result[4], "tokenModifiers should be preserved")
    }

    // ============================================
    // combine() tests
    // ============================================

    @Test
    fun `combine merges groovy and jenkins tokens`() {
        val groovyTokens = listOf(
            groovyToken(line = 0, startChar = 0, length = 5),
        )
        val jenkinsTokens = listOf(
            token(line = 1, startChar = 0, length = 3),
        )

        val result = SemanticTokensEncoder.combine(groovyTokens, jenkinsTokens)

        assertEquals(2, result.size)
    }

    @Test
    fun `combine handles empty groovy tokens`() {
        val jenkinsTokens = listOf(
            token(line = 0, startChar = 0, length = 5),
        )

        val result = SemanticTokensEncoder.combine(emptyList(), jenkinsTokens)

        assertEquals(1, result.size)
        assertEquals(jenkinsTokens[0], result[0])
    }

    @Test
    fun `combine handles empty jenkins tokens`() {
        val groovyTokens = listOf(
            groovyToken(line = 0, startChar = 0, length = 5, tokenType = 2),
        )

        val result = SemanticTokensEncoder.combine(groovyTokens, emptyList())

        assertEquals(1, result.size)
        assertEquals(2, result[0].tokenType)
    }

    @Test
    fun `combine converts groovy tokens to jenkins token format`() {
        val groovyTokens = listOf(
            groovyToken(line = 3, startChar = 7, length = 4, tokenType = 5, tokenModifiers = 2),
        )

        val result = SemanticTokensEncoder.combine(groovyTokens, emptyList())

        val converted = result[0]
        assertEquals(3, converted.line)
        assertEquals(7, converted.startChar)
        assertEquals(4, converted.length)
        assertEquals(5, converted.tokenType)
        assertEquals(2, converted.tokenModifiers)
    }

    @Test
    fun `combine handles both lists empty`() {
        val result = SemanticTokensEncoder.combine(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }
}
