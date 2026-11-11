package com.github.albertocavalcante.gvy.fmt

import groovyjarjarantlr4.v4.runtime.ANTLRErrorListener
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.RecognitionException
import groovyjarjarantlr4.v4.runtime.Recognizer
import groovyjarjarantlr4.v4.runtime.Token
import groovyjarjarantlr4.v4.runtime.Vocabulary
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError

/**
 * One token that carries meaning for the Groovy parser, as seen by [TokenStreamGuard].
 *
 * @property kind the lexer's symbolic token name, or [SEPARATOR] / [COMMENT] / [SHEBANG]
 * @property text the token text; comment text has its per-line indentation removed
 * @property line 1-based line of the token in its source
 * @property column 1-based column of the token in its source
 */
data class SignificantToken(val kind: String, val text: String, val line: Int, val column: Int) {
    internal fun sameAs(other: SignificantToken): Boolean = kind == other.kind && text == other.text

    override fun toString(): String = "$kind '${text.preview()}' at $line:$column"

    companion object {
        /** A run of one or more statement-separating line breaks. */
        const val SEPARATOR = "NEWLINE"

        /** A [SEPARATOR] right after `;`: an edit may add one, but never remove one. */
        const val SEPARATOR_AFTER_SEMICOLON = "NEWLINE_AFTER_SEMI"

        /** A line or block comment. */
        const val COMMENT = "COMMENT"

        /** The `#!` script header line. */
        const val SHEBANG = "SHEBANG"
    }
}

/** Why a source could not be tokenized. Positions are 1-based. */
data class LexFailure(val line: Int, val column: Int, val message: String) {
    override fun toString(): String = "$line:$column $message"
}

/** Outcome of comparing the token streams of an original and an edited source. */
sealed interface TokenStreamVerdict {
    /** True only when the edit provably changes nothing but layout. */
    val isSafe: Boolean get() = this is Preserved

    /** Short, single-line description for logs. */
    fun describe(): String

    /** Both sources have the same significant tokens; the edit only changes layout. */
    data object Preserved : TokenStreamVerdict {
        override fun describe(): String = "token stream preserved"
    }

    /**
     * The token streams differ. [expected] is the original token at the first difference and
     * [actual] the edited one; either is null when that stream ended first.
     */
    data class Diverged(val index: Int, val expected: SignificantToken?, val actual: SignificantToken?) :
        TokenStreamVerdict {
        override fun describe(): String = "first difference at token #$index: expected ${expected ?: "end of file"}, " +
            "found ${actual ?: "end of file"}"
    }

    /** The original lexes cleanly but the edited text does not: the edit introduced a syntax error. */
    data class IntroducedLexError(val failure: LexFailure) : TokenStreamVerdict {
        override fun describe(): String = "edit introduces a lexer error at $failure"
    }

    /** The original does not lex, so the edit cannot be verified. Treated as unsafe. */
    data class OriginalUnverifiable(val failure: LexFailure) : TokenStreamVerdict {
        override fun describe(): String = "original cannot be tokenized ($failure), so the edit cannot be verified"
    }
}

/**
 * Checks that an edit changes only layout, using the Groovy compiler's own lexer as ground truth.
 *
 * Pure and side-effect free, so any edit producer (formatting today; rename and code actions are
 * candidates) can apply its edits in memory and ask whether the result still means the same program.
 *
 * Two sources are equivalent when their significant token sequences are equal by kind and text.
 * What counts as layout, and is therefore ignored:
 * - spaces, tabs and line continuations between tokens (the lexer skips them);
 * - line breaks the parser ignores: the lexer hides those inside `(...)` and `[...]`;
 * - how many line breaks separate two tokens, and their style (`\n` versus `\r\n`): the grammar
 *   treats one line break and a run of them alike;
 * - line breaks in places where the grammar accepts optional newlines anyway: after `{` and `->`,
 *   and before `}`, `;`, `else`, `catch` and `finally`. Comments in between do not count, since
 *   the grammar absorbs them there too. A line break after `}`, or before `{` and `->`, stays
 *   significant: `foo {}` followed by `(x)` on the next line is a different program from
 *   `foo {} (x)`, and a `case` label must keep its `->` on the same line;
 * - a line break the edit adds after `;`. Removing one is a change: `if (c) ;` (an empty
 *   statement) cannot take the next statement on its line. Adding one is safe only because the
 *   original parses, so `;` followed by more code on its line was a separator; callers must not
 *   use the guard to vouch for edits to code that does not parse;
 * - other tokens the lexer puts on the hidden channel, which the parser never sees;
 * - indentation and trailing spaces on each line of a comment. Runtime groovydoc (a doc comment
 *   opened with an `@`) is kept at runtime, so re-indenting it is visible through reflection;
 *   we accept that;
 * - `\r\n` versus `\n` inside string literals: the compiler turns `\r\n` into `\n` in every
 *   literal that can span lines (triple-quoted, slashy and dollar-slashy strings and GStrings).
 *
 * Everything else is significant:
 * - every token's exact text, including whitespace inside string literals, GStrings, slashy and
 *   dollar-slashy strings and triple-quoted strings;
 * - comment text apart from per-line indentation;
 * - whether at least one statement-level line break separates two tokens, outside the places listed
 *   above. In Groovy a line break can end a statement (`return` followed by a line break returns
 *   null), so joining or splitting other lines is reported as a change even when harmless. This is
 *   deliberately conservative: a false alarm costs one skipped format, a missed change costs code;
 * - the leading `#!` script header lines, which the lexer itself skips.
 *
 * Because only tokens the parser ignores are normalised away, an edit with an equal token stream
 * cannot introduce a parse error, so no separate parse is needed. A lexer error that appears only
 * in the edited text is reported as [TokenStreamVerdict.IntroducedLexError].
 */
object TokenStreamGuard {

    /** Compares [original] with [edited] and returns the first difference, if any. */
    fun check(original: String, edited: String): TokenStreamVerdict {
        if (original == edited) return TokenStreamVerdict.Preserved

        val before = when (val lexed = significantTokens(original)) {
            is Lexed.Failed -> return TokenStreamVerdict.OriginalUnverifiable(lexed.failure)
            is Lexed.Tokens -> lexed.tokens
        }
        val after = when (val lexed = significantTokens(edited)) {
            is Lexed.Failed -> return TokenStreamVerdict.IntroducedLexError(lexed.failure)
            is Lexed.Tokens -> lexed.tokens
        }

        return firstDifference(before, after)
            ?.let { (index, actual) -> TokenStreamVerdict.Diverged(index, before.getOrNull(index), actual) }
            ?: TokenStreamVerdict.Preserved
    }

    /**
     * Walks both streams in step and returns the original index and edited token of the first
     * difference. The edit may add a line break after `;`: in code that parses, a `;` followed by
     * another statement on the same line is a separator, never an empty statement.
     */
    private fun firstDifference(
        before: List<SignificantToken>,
        after: List<SignificantToken>,
    ): Pair<Int, SignificantToken?>? {
        var i = 0
        var j = 0
        while (i < before.size || j < after.size) {
            val expected = before.getOrNull(i)
            val actual = after.getOrNull(j)
            when {
                expected != null && actual != null && expected.sameAs(actual) -> {
                    i++
                    j++
                }

                actual?.kind == SignificantToken.SEPARATOR_AFTER_SEMICOLON -> j++

                else -> return i to actual
            }
        }
        return null
    }

    internal sealed interface Lexed {
        data class Tokens(val tokens: List<SignificantToken>) : Lexed
        data class Failed(val failure: LexFailure) : Lexed
    }

    /** Lexes [source] into the significant token sequence described on [TokenStreamGuard]. */
    internal fun significantTokens(source: String): Lexed {
        val errors = FirstErrorListener()
        val lexer = GroovyLangLexer(CharStreams.fromString(source)).apply {
            removeErrorListeners()
            addErrorListener(errors)
        }
        val collector = SignificantTokenCollector(lexer.vocabulary, shebangToken(source))

        try {
            generateSequence { lexer.nextToken() }
                .takeWhile { it.type != Token.EOF }
                .forEach(collector::accept)
        } catch (error: GroovySyntaxError) {
            return Lexed.Failed(LexFailure(error.line, error.column, error.message ?: "syntax error"))
        } catch (error: RecognitionException) {
            return Lexed.Failed(
                LexFailure(lexer.line, lexer.charPositionInLine + 1, error.message ?: "no viable token"),
            )
        }

        return errors.first?.let { Lexed.Failed(it) }
            ?: Lexed.Tokens(markSeparatorsAfterSemicolons(dropOptionalSeparators(collector.tokens)))
    }

    // Token kinds whose neighbouring line breaks the grammar treats as optional (`nls` or `sep?`).
    // SEMI is deliberately absent: `;` can be an empty statement, so `if (c) ;` followed by a line break
    // and a statement differs from the same text on one line (a syntax error). See firstDifference.
    private val ABSORBS_FOLLOWING_LINE_BREAK = symbolicNames(GroovyLangLexer.LBRACE, GroovyLangLexer.ARROW)
    private val ABSORBS_PRECEDING_LINE_BREAK = symbolicNames(
        GroovyLangLexer.RBRACE,
        GroovyLangLexer.SEMI,
        GroovyLangLexer.ELSE,
        GroovyLangLexer.CATCH,
        GroovyLangLexer.FINALLY,
    )

    private val STRING_TOKEN_TYPES = setOf(
        GroovyLangLexer.StringLiteral,
        GroovyLangLexer.GStringBegin,
        GroovyLangLexer.GStringPart,
        GroovyLangLexer.GStringEnd,
    )

    private fun symbolicNames(vararg types: Int): Set<String> =
        types.toList().mapNotNull { GroovyLangLexer.VOCABULARY.getSymbolicName(it) }.toSet()

    private fun dropOptionalSeparators(tokens: List<SignificantToken>): List<SignificantToken> =
        tokens.filterIndexed { index, token ->
            token.kind != SignificantToken.SEPARATOR || !isOptionalSeparator(tokens, index)
        }

    private fun isOptionalSeparator(tokens: List<SignificantToken>, index: Int): Boolean {
        val before = tokens.subList(0, index).lastOrNull { it.isCode() }
        val after = tokens.subList(index + 1, tokens.size).firstOrNull { it.isCode() }
        return before?.kind in ABSORBS_FOLLOWING_LINE_BREAK || after?.kind in ABSORBS_PRECEDING_LINE_BREAK
    }

    private fun markSeparatorsAfterSemicolons(tokens: List<SignificantToken>): List<SignificantToken> =
        tokens.mapIndexed { index, token ->
            val afterSemicolon = token.kind == SignificantToken.SEPARATOR &&
                tokens.subList(0, index).lastOrNull { it.isCode() }?.kind in SEMICOLON
            if (afterSemicolon) token.copy(kind = SignificantToken.SEPARATOR_AFTER_SEMICOLON) else token
        }

    private val SEMICOLON = symbolicNames(GroovyLangLexer.SEMI)

    private fun SignificantToken.isCode(): Boolean = kind != SignificantToken.SEPARATOR &&
        kind != SignificantToken.SEPARATOR_AFTER_SEMICOLON &&
        kind != SignificantToken.COMMENT

    /**
     * Turns raw lexer tokens into significant tokens: drops layout and folds each run of
     * statement-level line breaks into one [SignificantToken.SEPARATOR] between two tokens.
     * [dropOptionalSeparators] then removes the separators the grammar treats as optional.
     */
    private class SignificantTokenCollector(private val vocabulary: Vocabulary, shebang: SignificantToken?) {
        val tokens: MutableList<SignificantToken> = listOfNotNull(shebang).toMutableList()
        private var pendingSeparator: Token? = null

        fun accept(token: Token) {
            val significant = classify(token)
            if (significant != null) {
                pendingSeparator?.takeIf { tokens.isNotEmpty() }?.let { tokens += separatorAt(it) }
                pendingSeparator = null
                tokens += significant
            } else if (isStatementLineBreak(token) && pendingSeparator == null) {
                pendingSeparator = token
            }
        }

        private fun classify(token: Token): SignificantToken? {
            val text = token.text.orEmpty()
            return when {
                // Hidden-channel tokens never reach the parser (for example GString look-ahead artefacts).
                token.type != GroovyLangLexer.NL && token.channel != Token.DEFAULT_CHANNEL -> null

                token.type in STRING_TOKEN_TYPES -> token.toSignificant(kindOf(token), text.replace("\r\n", "\n"))

                token.type != GroovyLangLexer.NL -> token.toSignificant(kindOf(token), text)

                // The Groovy lexer reports comments as NL tokens that keep the comment text.
                text.startsWith("//") || text.startsWith("/*") ->
                    token.toSignificant(SignificantToken.COMMENT, normalizeComment(text))

                else -> null
            }
        }

        private fun kindOf(token: Token): String = vocabulary.getSymbolicName(token.type) ?: token.type.toString()

        // Line breaks inside (...) and [...] are put on the hidden channel by the lexer: the parser ignores them.
        private fun isStatementLineBreak(token: Token): Boolean =
            token.type == GroovyLangLexer.NL && token.channel == Token.DEFAULT_CHANNEL

        private fun separatorAt(token: Token): SignificantToken = token.toSignificant(SignificantToken.SEPARATOR, "\n")

        private fun Token.toSignificant(kind: String, text: String): SignificantToken =
            SignificantToken(kind, text, line, charPositionInLine + 1)
    }

    /** Removes per-line indentation and trailing spaces, which re-indenting a block legitimately changes. */
    private fun normalizeComment(text: String): String = text.lineSequence().joinToString("\n") { it.trim() }

    private fun shebangToken(source: String): SignificantToken? {
        if (!source.startsWith("#!")) return null
        // The lexer skips the whole run of leading `#!` lines as one header.
        val header = source.lineSequence().takeWhile { it.startsWith("#!") }.joinToString("\n") { it.trimEnd() }
        return SignificantToken(SignificantToken.SHEBANG, header, line = 1, column = 1)
    }

    /** Records the first lexer error instead of printing it to stderr. */
    private class FirstErrorListener : ANTLRErrorListener<Int> {
        var first: LexFailure? = null
            private set

        override fun <T : Int> syntaxError(
            recognizer: Recognizer<T, *>?,
            offendingSymbol: T?,
            line: Int,
            charPositionInLine: Int,
            msg: String?,
            e: RecognitionException?,
        ) {
            if (first == null) first = LexFailure(line, charPositionInLine + 1, msg ?: "lexer error")
        }
    }
}

private const val PREVIEW_LIMIT = 60

private fun String.preview(): String {
    val escaped = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    return if (escaped.length <= PREVIEW_LIMIT) escaped else escaped.take(PREVIEW_LIMIT) + "..."
}
