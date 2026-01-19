package com.github.albertocavalcante.gvy.gls.providers.semantictokens

/**
 * Encodes semantic tokens for LSP protocol transmission.
 *
 * Extracted from GroovyTextDocumentService (#950) to improve testability
 * and reduce class size.
 *
 * LSP semantic tokens are encoded as a flat integer array where each token is
 * represented by 5 consecutive integers: [deltaLine, deltaStart, length, tokenType, modifiers]
 *
 * @see [LSP Semantic Tokens Spec](
 *     https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 * )
 */
object SemanticTokensEncoder {

    /**
     * Combine Groovy and Jenkins semantic tokens into a single unified list.
     *
     * Both token types use the same data structure, so we convert them to a common format
     * and merge them together for encoding.
     *
     * @param groovyTokens Tokens from [GroovySemanticTokenProvider]
     * @param jenkinsTokens Tokens from [JenkinsSemanticTokenProvider]
     * @return Combined list in [JenkinsSemanticTokenProvider.SemanticToken] format
     */
    fun combine(
        groovyTokens: List<GroovySemanticTokenProvider.SemanticToken>,
        jenkinsTokens: List<JenkinsSemanticTokenProvider.SemanticToken>,
    ): List<JenkinsSemanticTokenProvider.SemanticToken> {
        // Convert GroovySemanticTokenProvider tokens to JenkinsSemanticTokenProvider tokens
        val convertedGroovyTokens = groovyTokens.map { token ->
            JenkinsSemanticTokenProvider.SemanticToken(
                line = token.line,
                startChar = token.startChar,
                length = token.length,
                tokenType = token.tokenType,
                tokenModifiers = token.tokenModifiers,
            )
        }

        return convertedGroovyTokens + jenkinsTokens
    }

    /**
     * Encode semantic tokens using LSP relative encoding format.
     *
     * Encoding rules:
     * - deltaLine: Line offset from previous token (0 if same line)
     * - deltaStart: If deltaLine == 0, offset from previous token's start
     *               If deltaLine > 0, absolute column position (reset)
     * - length: Token length in characters
     * - tokenType: Index into SemanticTokensLegend.tokenTypes
     * - modifiers: Bitfield of indices into SemanticTokensLegend.tokenModifiers
     *
     * NOTE: Tokens are sorted by line, then by startChar within each line.
     *
     * Example:
     * ```
     * Input:  [Token(line=0, char=0, len=8), Token(line=0, char=10, len=5)]
     * Output: [0, 0, 8, type, 0,  0, 10, 5, type, 0]
     *          ^--token 1-----^   ^--token 2-----^
     * ```
     *
     * @param tokens List of semantic tokens to encode
     * @return Flat list of integers in LSP encoding format (5 integers per token)
     */
    fun encode(tokens: List<JenkinsSemanticTokenProvider.SemanticToken>): List<Int> {
        if (tokens.isEmpty()) {
            return emptyList()
        }

        val encoded = mutableListOf<Int>()
        var prevLine = 0
        var prevChar = 0

        // Sort tokens by line, then by character
        val sortedTokens = tokens.sortedWith(compareBy({ it.line }, { it.startChar }))

        sortedTokens.forEach { token ->
            // Calculate delta line
            val deltaLine = token.line - prevLine

            // Calculate delta char (depends on whether we changed lines)
            val deltaChar = if (deltaLine == 0) {
                // Same line: relative to previous token
                token.startChar - prevChar
            } else {
                // New line: absolute position (reset)
                token.startChar
            }

            // Add encoded token (5 integers)
            encoded.add(deltaLine)
            encoded.add(deltaChar)
            encoded.add(token.length)
            encoded.add(token.tokenType)
            encoded.add(token.tokenModifiers)

            // Update tracking for next token
            prevLine = token.line
            prevChar = token.startChar
        }

        return encoded
    }
}
