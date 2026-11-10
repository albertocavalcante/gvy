package com.github.albertocavalcante.groovyparser.api.untrusted

import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovyLexer
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import java.io.StringReader

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
 *   minutes of CPU for thousands of nested closures and overflows its stack on deeper input). Nesting is counted
 *   from Groovy's own lexer, so GString interpolation, slashy strings and comments are handled exactly as the
 *   parser sees them.
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

    /**
     * Deepest `{` / `(` / `[` nesting in [text], counted from Groovy's own lexer so that GString interpolation
     * (`"${ ... }"`), slashy and dollar-slashy strings, triple-quoted strings and comments are tokenised exactly
     * as the parser will see them (a hand-written scan is unsound for those). Lexing is linear and does not
     * recurse, so it is safe on hostile input; unlexable input simply ends the scan.
     */
    private fun maxNesting(text: String): Int {
        val lexer = GroovyLangLexer(StringReader(text))
        lexer.removeErrorListeners()
        var depth = 0
        var max = 0
        while (true) {
            val token = lexer.nextToken()
            when {
                token.type == Token.EOF -> return max
                opens(token) -> max = maxOf(max, ++depth)
                closes(token.type) -> depth = maxOf(0, depth - 1)
            }
        }
    }

    private fun opens(token: Token): Boolean = token.type in OPENERS ||
        (token.type == GroovyLexer.GStringPart && token.text.endsWith("{"))

    private fun closes(type: Int): Boolean = type in CLOSERS

    private val OPENERS = setOf(GroovyLexer.LPAREN, GroovyLexer.LBRACE, GroovyLexer.LBRACK)
    private val CLOSERS = setOf(GroovyLexer.RPAREN, GroovyLexer.RBRACE, GroovyLexer.RBRACK)
}
