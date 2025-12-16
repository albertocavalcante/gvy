package com.github.albertocavalcante.groovylsp.providers.semantictokens

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for JenkinsSemanticTokenProvider.
 *
 * NOTE: Block recognition is tested in JenkinsBlockMetadataTest (groovy-jenkins module).
 * These tests focus on token generation and type mapping.
 */
class JenkinsSemanticTokenProviderTest {

    @Test
    fun `SemanticToken data class should be properly constructible`() {
        val token = JenkinsSemanticTokenProvider.SemanticToken(
            line = 10,
            startChar = 5,
            length = 15,
            tokenType = JenkinsSemanticTokenProvider.TokenTypes.MACRO,
            tokenModifiers = 0,
        )

        assertEquals(10, token.line)
        assertEquals(5, token.startChar)
        assertEquals(15, token.length)
        assertEquals(JenkinsSemanticTokenProvider.TokenTypes.MACRO, token.tokenType)
        assertEquals(0, token.tokenModifiers)
    }

    @Test
    fun `token types should have distinct values`() {
        val tokenTypes = listOf(
            JenkinsSemanticTokenProvider.TokenTypes.FUNCTION,
            JenkinsSemanticTokenProvider.TokenTypes.METHOD,
            JenkinsSemanticTokenProvider.TokenTypes.MACRO,
            JenkinsSemanticTokenProvider.TokenTypes.DECORATOR,
        )

        // All should be distinct
        assertEquals(tokenTypes.size, tokenTypes.toSet().size, "Token types should be distinct")
    }

    @Test
    fun `token types should match LSP spec indices`() {
        // These indices MUST match the legend order in GroovyLanguageServer
        assertEquals(12, JenkinsSemanticTokenProvider.TokenTypes.FUNCTION)
        assertEquals(13, JenkinsSemanticTokenProvider.TokenTypes.METHOD)
        assertEquals(14, JenkinsSemanticTokenProvider.TokenTypes.MACRO)
        assertEquals(22, JenkinsSemanticTokenProvider.TokenTypes.DECORATOR)
    }
}
