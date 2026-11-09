package com.github.albertocavalcante.gvy.gls.providers.completion

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionContextDetectorCommandExpressionTest {
    private fun detect(line: String, method: String, cursor: Int = line.length) =
        CompletionContextDetector.isCommandExpression(line, 0, cursor, method)

    @Test
    fun `detects single-quoted command argument being typed`() = assertTrue(detect("sh 'ls", "sh"))

    @Test
    fun `detects double-quoted command argument being typed`() = assertTrue(detect("println \"x", "println"))

    @Test
    fun `detects with cursor inside a closed string`() = assertTrue(detect("sh 'ls'", "sh", cursor = 6))

    @Test
    fun `detects after leading indentation and extra spaces`() = assertTrue(detect("    echo   'foo", "echo"))

    @Test
    fun `detects empty string just opened`() = assertTrue(detect("echo '", "echo"))

    @Test
    fun `does not match unquoted argument`() = assertFalse(detect("echo foo", "echo"))

    @Test
    fun `does not match parenthesised call`() = assertFalse(detect("sh('ls", "sh"))

    @Test
    fun `does not match a different method name`() = assertFalse(detect("shell 'ls", "sh"))

    @Test
    fun `does not match when string is already closed before cursor`() = assertFalse(detect("sh 'ls'", "sh"))

    @Test
    fun `does not match method name containing regex metacharacters`() = assertFalse(detect("sh 'ls", "s.", 6))

    @Test
    fun `out of range position is false`() =
        assertFalse(CompletionContextDetector.isCommandExpression("sh 'ls", 5, 0, "sh"))
}
