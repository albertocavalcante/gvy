package com.github.albertocavalcante.gvy.fmt

import com.github.albertocavalcante.gvy.common.text.ShebangUtils
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.format.AutoFormat
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space
import org.slf4j.LoggerFactory
import java.util.stream.Collectors

class OpenRewriteFormatter internal constructor(
    private val checker: (original: String, edited: String) -> TokenStreamVerdict,
) {
    constructor() : this(TokenStreamGuard::check)

    private val parser: GroovyParser = GroovyParser.builder().build()

    // Report recipe errors through the server log: a raw printStackTrace() bypasses Logback and its levels.
    internal val executionContext: ExecutionContext = InMemoryExecutionContext { t: Throwable ->
        logger.error("OpenRewrite formatting recipe failed", t)
    }
    private val recipe = AutoFormat()

    /**
     * Formats [text], or returns it unchanged when formatting would change more than layout.
     *
     * Same as [formatChecked], for callers that only need the text. A refusal is logged at WARN
     * with the first token difference; callers that know the document should use [formatChecked]
     * and report the refusal themselves.
     */
    fun format(text: String): String {
        val outcome = formatChecked(text)
        if (outcome is FormatOutcome.Refused) {
            logger.warn("Refusing formatting result that would change the program: {}", outcome.reason, outcome.cause)
        }
        return outcome.text
    }

    /**
     * Formats [text] and checks the result with [TokenStreamGuard] before it leaves the formatter,
     * so the LSP formatting request, the "Format document" code action and `gls format` all share
     * the check. Does not log refusals: the caller knows which document they belong to.
     */
    fun formatChecked(text: String): FormatOutcome {
        val formatted = formatUnchecked(text)
        if (formatted == text) return FormatOutcome.Unchanged(text)

        val verdict = runCatching { checker(text, formatted) }.getOrElse { failure ->
            // A guard that cannot run cannot vouch for the edit.
            return FormatOutcome.Refused(text, "the token stream check failed: $failure", failure)
        }
        return if (verdict.isSafe) {
            FormatOutcome.Formatted(formatted)
        } else {
            FormatOutcome.Refused(text, verdict.describe())
        }
    }

    /**
     * The formatter's own output, before [TokenStreamGuard] checks it.
     *
     * Tests use this to assert what the formatter produces, independent of whether the guard would refuse it.
     */
    internal fun formatUnchecked(text: String): String {
        if (text.isBlank()) return text

        // NOTE: Shebang extraction is a heuristic workaround. OpenRewrite's GroovyParser doesn't
        // recognize shebangs because they're Unix shell directives, not Groovy syntax. We extract
        // them before parsing to prevent mangling, then restore them after formatting.
        //
        // Delegated to shared ShebangUtils to avoid duplication.
        val extraction = ShebangUtils.extractShebang(text)

        // Handle shebang-only files (no content to format)
        if (extraction.shebang != null && extraction.content.isBlank()) {
            return extraction.shebang!!.trimEnd()
        }

        val formattedText = runCatching {
            val sourceFiles = parser.parse(extraction.content).collect(Collectors.toList())
            val formattedSource = recipe.run(InMemoryLargeSourceSet(sourceFiles), executionContext)
                .changeset
                .allResults
                .mapNotNull { it.after }
                .firstOrNull()
                ?: return@runCatching extraction.content

            // Fix spacing on the LST only: a text-level pass over the printed source would also rewrite
            // the inside of string literals and comments (#1524).
            val declarationsFixed = DeclarationSpacing().visit(formattedSource, Unit) as SourceFile
            val normalizedSource = PostFormatWhitespaceCollapser().visit(declarationsFixed, Unit) as SourceFile
            normalizedSource.printAll().normalizeLineEndings()
        }.getOrElse { return text }

        // Restore shebang with normalized spacing (always one blank line)
        return extraction.shebang?.let { shebang ->
            "$shebang\n${formattedText.trimStart()}"
        } ?: formattedText
    }

    /**
     * Normalizes line endings to Unix style (LF) for consistency.
     */
    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

    /**
     * Visitor that collapses multiple consecutive spaces within code.
     *
     * TODO(#formatter-upstream): once OpenRewrite collapses redundant spaces for Groovy scripts, drop this visitor.
     */
    private class PostFormatWhitespaceCollapser : GroovyIsoVisitor<Unit>() {
        override fun visitSpace(space: Space?, loc: Space.Location, p: Unit): Space {
            val actualSpace = space ?: return super.visitSpace(null, loc, p)

            val shouldCollapse = actualSpace.whitespace.length > 1 &&
                '\n' !in actualSpace.whitespace &&
                actualSpace.comments.isEmpty()

            val updatedSpace = if (shouldCollapse) {
                val collapsed = MULTIPLE_SPACES_REGEX.replace(actualSpace.whitespace, " ")
                actualSpace.withWhitespace(collapsed)
            } else {
                actualSpace
            }

            return super.visitSpace(updatedSpace, loc, p)
        }

        companion object {
            private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
        }
    }

    /**
     * Removes the extra spaces `AutoFormat` puts into variable and parameter declarations (#1524).
     *
     * rewrite-groovy stores the space in front of a declared name in the name's own prefix, and gives an
     * untyped declaration (`def x = 1`, the parameter in `f(x)`) an empty identifier as its type.
     * `AutoFormat` then adds a space in the variable's prefix as well, so `String s` prints as
     * `String  s`, `def x` as `def  x` and `f(x)` as `f( x)`. Each of those runs is split across
     * adjacent LST nodes, so [PostFormatWhitespaceCollapser] cannot see it as one run. This visitor
     * merges the adjacent prefixes: a name keeps one space after its type or modifier, and none when
     * nothing precedes it within the declaration.
     */
    private class DeclarationSpacing : GroovyIsoVisitor<Unit>() {
        override fun visitVariable(
            variable: J.VariableDeclarations.NamedVariable,
            p: Unit,
        ): J.VariableDeclarations.NamedVariable {
            val named = super.visitVariable(variable, p)
            val namePrefix = named.name.prefix
            val splitRun = named.prefix.isInlineWhitespace() && namePrefix.isInlineWhitespace() &&
                namePrefix.whitespace.isNotEmpty()
            if (!splitRun) return named
            // The variable's prefix and its name's prefix print back to back; keep one of them.
            val merged = if (named.prefix.whitespace.isEmpty()) namePrefix else named.prefix
            return named.withPrefix(merged).withName(named.name.withPrefix(Space.EMPTY))
        }

        override fun visitVariableDeclarations(
            multiVariable: J.VariableDeclarations,
            p: Unit,
        ): J.VariableDeclarations {
            val declarations = super.visitVariableDeclarations(multiVariable, p)
            val type = declarations.typeExpression as? J.Identifier
            val firstVariable = declarations.variables.firstOrNull()
            if (type == null || firstVariable == null || !isUntypedWithInlineSpacing(type, firstVariable)) {
                return declarations
            }

            // The empty type prints nothing, so only a modifier or annotation needs a space after it.
            val precededByText = declarations.leadingAnnotations.isNotEmpty() || declarations.modifiers.isNotEmpty()
            val variablePrefix = if (precededByText) Space.SINGLE_SPACE else Space.EMPTY
            return declarations
                .withTypeExpression(type.withPrefix(Space.EMPTY))
                .withVariables(listOf(firstVariable.withPrefix(variablePrefix)) + declarations.variables.drop(1))
        }

        private fun isUntypedWithInlineSpacing(
            type: J.Identifier,
            firstVariable: J.VariableDeclarations.NamedVariable,
        ): Boolean = type.simpleName.isEmpty() && type.annotations.isEmpty() &&
            type.prefix.isInlineWhitespace() && firstVariable.prefix.isInlineWhitespace()

        private fun Space.isInlineWhitespace(): Boolean = comments.isEmpty() && '\n' !in whitespace
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OpenRewriteFormatter::class.java)
    }
}
