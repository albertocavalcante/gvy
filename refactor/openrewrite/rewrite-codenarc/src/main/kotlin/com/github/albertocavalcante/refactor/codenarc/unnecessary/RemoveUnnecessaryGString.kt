package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J

/**
 * Recipe to convert unnecessary GStrings (double-quoted strings without expressions)
 * to regular single-quoted strings.
 *
 * This aligns with CodeNarc rule: UnnecessaryGString
 *
 * In Groovy, double-quoted strings are GStrings that support interpolation.
 * If no interpolation is used, a regular single-quoted string is preferred
 * for clarity and minor performance benefits.
 *
 * Note: This recipe preserves GStrings that contain single quotes to avoid
 * breaking the string syntax.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarygstring">CodeNarc Rule</a>
 */
class RemoveUnnecessaryGString : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary GString"

    override fun getDescription(): String = "Converts GStrings (double-quoted) without interpolation expressions to " +
        "regular strings (single-quoted) for clarity."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyVisitor<ExecutionContext>() {

            @Suppress("ReturnCount") // Guard clauses for early exit validation
            override fun visitGString(gString: G.GString, ctx: ExecutionContext): J {
                val g = super.visitGString(gString, ctx) as G.GString

                // Only convert if the GString has no interpolation (only string parts)
                val strings = g.strings
                if (strings.size != 1) {
                    // Multiple parts means there's at least one expression
                    return g
                }

                val onlyPart = strings[0]
                if (onlyPart !is J.Literal) {
                    // If it's not a simple literal, it has expressions
                    return g
                }

                val stringValue = onlyPart.value as? String ?: return g

                // Don't convert if the string contains single quotes (would break syntax)
                if (stringValue.contains("'")) {
                    return g
                }

                // Convert to single-quoted string
                val newValueSource = "'$stringValue'"

                return onlyPart.withValueSource(newValueSource)
                    .withPrefix(g.prefix)
                    .withMarkers(g.markers)
            }
        }
    }
}
