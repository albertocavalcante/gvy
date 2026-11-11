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
 * - indentation and trailing spaces on each line of a comment.
 *
 * Everything else is significant:
 * - every token's exact text, including whitespace inside string literals, GStrings, slashy and
 *   dollar-slashy strings and triple-quoted strings;
 * - comment text apart from per-line indentation;
 * - whether at least one statement-level line break separates two tokens. In Groovy a line break
 *   can end a statement (`return` followed by a line break returns null), so joining or splitting
 *   lines outside brackets is reported as a change even when it would be harmless. This is
 *   deliberately conservative: a false alarm costs one skipped format, a missed change costs code;
 * - the `#!` script header, which the lexer itself skips.
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

        val firstDifference = before.indices.firstOrNull { index ->
            index >= after.size || !before[index].sameAs(after[index])
        } ?: if (after.size > before.size) before.size else null

        return firstDifference?.let { index ->
            TokenStreamVerdict.Diverged(index, before.getOrNull(index), after.getOrNull(index))
        } ?: TokenStreamVerdict.Preserved
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

        return errors.first?.let { Lexed.Failed(it) } ?: Lexed.Tokens(collector.tokens)
    }

    /**
     * Turns raw lexer tokens into significant tokens: drops layout and folds each run of
     * statement-level line breaks into one [SignificantToken.SEPARATOR] between two tokens.
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
                token.type != GroovyLangLexer.NL ->
                    token.toSignificant(vocabulary.getSymbolicName(token.type) ?: token.type.toString(), text)

                // The Groovy lexer reports comments as NL tokens that keep the comment text.
                text.startsWith("//") || text.startsWith("/*") ->
                    token.toSignificant(SignificantToken.COMMENT, normalizeComment(text))

                else -> null
            }
        }

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
        val header = source.lineSequence().first().trimEnd()
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
