package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.Cursor
import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JLeftPadded
import org.openrewrite.java.tree.JavaSourceFile
import org.openrewrite.java.tree.Space
import org.openrewrite.marker.Markers

/**
 * Recipe to replace explicit getter calls with property access in Groovy.
 *
 * This aligns with CodeNarc rule: UnnecessaryGetter
 *
 * e.g. obj.getName() -> obj.name
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarygetter">CodeNarc Rule</a>
 */
class RemoveUnnecessaryGetter : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary getter"

    override fun getDescription(): String =
        "Replaces explicit getter calls with property access (e.g. obj.getName() -> obj.name)."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {

        private val spockMethods = setOf("Mock", "Spy", "Stub")

        override fun visitMethodInvocation(method: J.MethodInvocation, ctx: ExecutionContext): J {
            val m = super.visitMethodInvocation(method, ctx) as J.MethodInvocation

            // Skip if has arguments (J.Empty represents empty argument list)
            if (m.arguments.isNotEmpty() && m.arguments.first() !is J.Empty) {
                return m
            }

            val name = m.name.simpleName

            // Exclusions
            if (name == "getClass") return m
            if (isInSpockMock(cursor)) return m

            // Extract property name from getter
            val propertyName = extractPropertyName(name) ?: return m

            // Build replacement AST nodes
            val newIdentifier = J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                emptyList(),
                propertyName,
                m.type,
                null,
            )

            return m.select?.let { select ->
                J.FieldAccess(
                    Tree.randomId(),
                    m.prefix,
                    m.markers,
                    select,
                    JLeftPadded.build(newIdentifier),
                    m.type,
                )
            } ?: newIdentifier.withPrefix(m.prefix).withMarkers(m.markers)
        }

        /**
         * Extracts property name from getter method name.
         * Returns null if not a valid getter pattern.
         */
        private fun extractPropertyName(name: String): String? = when {
            name.startsWith("get") && name.length > 3 && name[3].isUpperCase() -> {
                val suffix = name.substring(3)
                // Skip getURL, getXML patterns (consecutive uppercase)
                if (suffix.length > 1 && suffix[1].isUpperCase()) {
                    null
                } else {
                    suffix.replaceFirstChar { it.lowercase() }
                }
            }

            name.startsWith("is") && name.length > 2 && name[2].isUpperCase() -> {
                val suffix = name.substring(2)
                if (suffix.length > 1 && suffix[1].isUpperCase()) {
                    null
                } else {
                    suffix.replaceFirstChar { it.lowercase() }
                }
            }

            else -> null
        }

        /**
         * Checks if we're inside a Spock Mock/Spy/Stub closure.
         */
        private fun isInSpockMock(cursor: Cursor): Boolean {
            var c: Cursor? = cursor
            while (c != null) {
                val value = c.getValue<Any>()
                if (value is JavaSourceFile) break
                if (value is J.MethodInvocation && value.name.simpleName in spockMethods) {
                    return true
                }
                c = c.parent
            }
            return false
        }
    }
}
