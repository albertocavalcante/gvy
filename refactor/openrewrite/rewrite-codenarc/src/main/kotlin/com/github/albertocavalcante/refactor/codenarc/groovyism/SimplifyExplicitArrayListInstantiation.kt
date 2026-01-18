package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JContainer
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.marker.Markers
import java.util.Collections

/**
 * Recipe to simplify explicit ArrayList instantiation to Groovy list literal.
 *
 *
 * e.g. new ArrayList() -> []
 *
 * @see <a href="https://codenarc.github.io/CodeNarc/codenarc-rules-groovyism.html#explicitarraylistinstantiation">
 *     CodeNarc Rule</a>
 */
class SimplifyExplicitArrayListInstantiation : Recipe() {

    override fun getDisplayName(): String = "Simplify explicit ArrayList instantiation"

    override fun getDescription(): String =
        "Replaces explicit ArrayList instantiation with Groovy list literal (e.g. new ArrayList() -> [])."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
        override fun visitNewClass(newClass: J.NewClass, ctx: ExecutionContext): J {
            var nc = super.visitNewClass(newClass, ctx) as J.NewClass

            // Check if type is ArrayList
            val type = nc.clazz
            val isArrayList = when (type) {
                is J.Identifier -> type.simpleName == "ArrayList"
                // Handle fully qualified names like java.util.ArrayList
                is J.FieldAccess -> type.name.simpleName == "ArrayList"
                is J.ParameterizedType -> {
                    val clazz = type.clazz
                    if (clazz is J.Identifier) {
                        clazz.simpleName == "ArrayList"
                    } else if (clazz is J.FieldAccess) {
                        clazz.name.simpleName == "ArrayList"
                    } else {
                        false
                    }
                }

                else -> false
            }

            if (!isArrayList) {
                return nc
            }

            // Check if arguments are empty (default constructor)
            val args = nc.arguments
            val isDefaultConstructor = args.isEmpty() || (args.size == 1 && args[0] is J.Empty)

            if (!isDefaultConstructor) {
                return nc
            }

            // Replace explicit ArrayList instantiation with empty list literal []
            // Represented as G.ListLiteral with no elements.
            return G.ListLiteral(
                Tree.randomId(),
                nc.prefix,
                Markers.EMPTY,
                JContainer.build(Collections.emptyList<JRightPadded<Expression>>()),
                null, // Type will be inferred or null
            )
        }
    }
}
