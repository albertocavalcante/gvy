package com.github.albertocavalcante.gvy.gls.providers.completion

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A "command expression" is a paren-less call whose first positional argument is complete and the cursor
 * is right after it (`sh 'ls' |`), which is where map-style options must be introduced with a `, `.
 */
class CompletionContextDetectorCommandExpressionTest {
    private fun detect(line: String, method: String, cursor: Int = line.length) =
        CompletionContextDetector.isCommandExpression(line, 0, cursor, method)

    @Test
    fun `detects after complete single-quoted argument`() = assertTrue(detect("sh 'ls'", "sh"))

    @Test
    fun `detects after complete double-quoted argument`() = assertTrue(detect("println \"x\"", "println"))

    @Test
    fun `detects with trailing whitespace after the argument`() = assertTrue(detect("sh 'ls' ", "sh"))

    @Test
    fun `detects after indentation and extra spaces`() = assertTrue(detect("    echo   'foo'", "echo"))

    @Test
    fun `detects after triple-quoted argument`() {
        assertTrue(detect("sh '''ls -l'''", "sh"))
        assertTrue(detect("sh \"\"\"ls -l\"\"\"", "sh"))
    }

    @Test
    fun `detects argument containing escaped quote`() = assertTrue(detect("echo 'it\\'s'", "echo"))

    @Test
    fun `detects triple-quoted argument containing a lone quote`() = assertTrue(detect("sh '''echo 'x' '''", "sh"))

    @Test
    fun `detects with a partially typed option name after the argument`() {
        assertTrue(detect("sh 'ls' ret", "sh"))
        assertFalse(detect("sh 'ls'ret", "sh"))
    }

    @Test
    fun `does not match while the string is still open`() {
        assertFalse(detect("sh 'ls", "sh"))
        assertFalse(detect("println \"x", "println"))
        assertFalse(detect("sh '''ls", "sh"))
    }

    @Test
    fun `does not match with cursor inside a closed string`() = assertFalse(detect("sh 'ls'", "sh", cursor = 6))

    @Test
    fun `does not match when a comma already follows the argument`() = assertFalse(detect("sh 'ls',", "sh"))

    @Test
    fun `does not match unquoted argument`() = assertFalse(detect("echo foo", "echo"))

    @Test
    fun `does not match parenthesised call`() = assertFalse(detect("sh('ls'", "sh"))

    @Test
    fun `does not match a different method name`() = assertFalse(detect("shell 'ls'", "sh"))

    @Test
    fun `out of range position is false`() =
        assertFalse(CompletionContextDetector.isCommandExpression("sh 'ls'", 5, 0, "sh"))

    @Test
    fun `isInsideStringLiteral tracks quotes before the cursor`() {
        fun inside(line: String, cursor: Int = line.length) =
            CompletionContextDetector.isInsideStringLiteral(line, 0, cursor)

        assertTrue(inside("sh 'ls"))
        assertTrue(inside("sh 'ls'", cursor = 6))
        assertTrue(inside("sh \"echo \\\"x"))
        assertFalse(inside("sh 'ls'"))
        assertFalse(inside("sh 'ls' "))
        assertFalse(inside("sh '''ls'''"))
        assertTrue(inside("sh '''ls"))
        assertTrue(inside("sh '''it's"))
        assertFalse(inside("sh '''it's ok'''"))
        assertFalse(inside("echo // it's"))
    }

    @Test
    fun `followsMemberAccessDot distinguishes dot access from command expressions`() {
        fun dot(text: String, line: Int = 0, cursor: Int = text.lines()[line].length) =
            followsMemberAccessDot(text, line, cursor)

        assertTrue(dot("list."))
        assertTrue(dot("list.si"))
        assertTrue(dot("list?."))
        assertTrue(dot("list.\n  size()", line = 1, cursor = 2))
        assertFalse(dot("sh 'ls' "))
        assertFalse(dot("sh 'ls'"))
        assertFalse(dot("sh"))
        assertTrue(dot("list. // trailing\n", line = 1, cursor = 0))
        assertTrue(dot("list. /* c */ ", cursor = 14))
        assertTrue(dot("println \"http://x\"."))
        assertFalse(dot("sh 'ls' // see foo."))
    }
}
