package com.github.albertocavalcante.groovyparser.api.untrusted

import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

/**
 * Limits applied to untrusted Groovy before and after it is parsed. Defaults suit a small hand-written config
 * file; callers that read big generated files (for example a Jenkins GDSL dump) pass larger values.
 */
data class UntrustedParseLimits(
    val maxChars: Int = DEFAULT_MAX_CHARS,
    /** Deepest `{` / `(` / `[` nesting accepted; checked before the parser sees the text. */
    val maxNesting: Int = 8,
    /** Statements anywhere in the file, including those inside closures. */
    val maxStatements: Int = DEFAULT_MAX_STATEMENTS,
)

private const val DEFAULT_MAX_CHARS = 64 * 1024
private const val DEFAULT_MAX_STATEMENTS = 2_000
private const val TRIPLE = 3
private const val COMMENT_OPEN = 2

/** Result of [UntrustedAstParser.parse]. */
sealed interface UntrustedParseResult {
    data class Parsed(val module: ModuleNode) : UntrustedParseResult

    /** [line] and [column] are 1-based when the parser reported a position. */
    data class Rejected(val reason: String, val line: Int? = null, val column: Int? = null) : UntrustedParseResult
}

/**
 * Parses Groovy that came from an untrusted place (a workspace file) into an AST so a caller can read a
 * declarative subset out of it, **without ever running it**.
 *
 * - No `CompilationUnit` is created and no phase operation runs, so no AST transform (global or local, `@Grab`,
 *   `@ASTTest`, ...) can execute and no class is generated or loaded; only parse and convert are performed.
 *   Applying `AnalysisCompilerPolicy` is therefore unnecessary here.
 * - The text is bounded before the parser sees it: a character cap and a nesting cap (the Groovy parser needs
 *   minutes of CPU for thousands of nested closures and overflows its stack on deeper input). The nesting scan
 *   counts `{`, `(` and `[` outside string literals and comments; slashy strings are not recognised.
 * - After parsing, statements are counted recursively (closures included) against [UntrustedParseLimits.maxStatements].
 * - `CompilationFailedException` and `StackOverflowError` become a [UntrustedParseResult.Rejected].
 *
 * Allow-lists stay with each caller; this only provides the safe parse and the limits.
 */
object UntrustedAstParser {
    fun parse(
        source: String,
        name: String,
        limits: UntrustedParseLimits = UntrustedParseLimits(),
    ): UntrustedParseResult {
        val tooBig = "larger than ${limits.maxChars} characters".takeIf { source.length > limits.maxChars }
        val tooDeep = "nested deeper than ${limits.maxNesting} levels"
            .takeIf { tooBig == null && maxNesting(source) > limits.maxNesting }
        return when {
            tooBig != null -> UntrustedParseResult.Rejected(tooBig)
            tooDeep != null -> UntrustedParseResult.Rejected(tooDeep)
            else -> parseBounded(source, name, limits)
        }
    }

    private fun parseBounded(source: String, name: String, limits: UntrustedParseLimits): UntrustedParseResult = try {
        val module = convert(source, name)
        val statements = countStatements(module)
        if (statements > limits.maxStatements) {
            UntrustedParseResult.Rejected("more than ${limits.maxStatements} statements")
        } else {
            UntrustedParseResult.Parsed(module)
        }
    } catch (e: MultipleCompilationErrorsException) {
        val syntax = e.errorCollector.errors?.filterIsInstance<SyntaxErrorMessage>()?.firstOrNull()?.cause
        UntrustedParseResult.Rejected(
            "not parseable: ${syntax?.originalMessage ?: e.message?.lineSequence()?.firstOrNull()}",
            syntax?.line?.takeIf { it > 0 },
            syntax?.startColumn?.takeIf { it > 0 },
        )
    } catch (e: CompilationFailedException) {
        UntrustedParseResult.Rejected("not parseable: ${e.message?.lineSequence()?.firstOrNull()}")
    } catch (_: StackOverflowError) {
        UntrustedParseResult.Rejected("too deeply nested")
    }

    private fun convert(source: String, name: String): ModuleNode {
        val config = CompilerConfiguration()
        return SourceUnit(name, source, config, null, ErrorCollector(config)).run {
            parse()
            completePhase()
            nextPhase()
            convert()
            ast
        }
    }

    private fun countStatements(module: ModuleNode): Int {
        var count = 0
        val counter = object : CodeVisitorSupport() {
            override fun visitBlockStatement(block: BlockStatement) {
                count += block.statements.size
                super.visitBlockStatement(block)
            }
        }
        module.statementBlock.visit(counter)
        return count
    }

    /** Deepest `{` / `(` / `[` nesting in [text], ignoring string literals and comments. */
    private fun maxNesting(text: String): Int {
        var depth = 0
        var max = 0
        var i = 0
        while (i < text.length) {
            val skipTo = skippedEnd(text, i)
            if (skipTo > i) {
                i = skipTo
                continue
            }
            when (text[i]) {
                '{', '(', '[' -> max = maxOf(max, ++depth)
                '}', ')', ']' -> depth = maxOf(0, depth - 1)
            }
            i++
        }
        return max
    }

    /** End index (exclusive) of the string literal or comment starting at [i], or [i] if none starts there. */
    private fun skippedEnd(text: String, i: Int): Int {
        val c = text[i]
        val triple = c in "'\"" && text.startsWith("$c$c$c", i)
        return when {
            text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
            text.startsWith("/*", i) -> endOf(text, "*/", i + COMMENT_OPEN)
            triple -> endOf(text, "$c$c$c", i + TRIPLE)
            c == '\'' || c == '"' -> endOfQuoted(text, i, c)
            else -> i
        }
    }

    /** Index just past the next [terminator] at or after [from], or the end of [text] if it never closes. */
    private fun endOf(text: String, terminator: String, from: Int): Int =
        text.indexOf(terminator, from).let { if (it < 0) text.length else it + terminator.length }

    private fun endOfQuoted(text: String, start: Int, quote: Char): Int {
        var j = start + 1
        while (j < text.length && text[j] != quote && text[j] != '\n') {
            j += if (text[j] == '\\') 2 else 1
        }
        return minOf(j + 1, text.length)
    }
}
