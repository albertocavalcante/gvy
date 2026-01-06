package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JContainer
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Space
import org.openrewrite.marker.Markers
import java.util.Collections

/**
 * Recipe to simplify explicit HashMap/LinkedHashMap instantiation to Groovy map literal.
 *
 * This aligns with CodeNarc rule: ExplicitHashMapInstantiation
 *
 * e.g. new HashMap() -> [:]
 *      new LinkedHashMap() -> [:]
 */
class SimplifyExplicitHashMapInstantiation : Recipe() {

    override fun getDisplayName(): String = "Simplify explicit HashMap instantiation"

    override fun getDescription(): String =
        "Replaces explicit HashMap/LinkedHashMap instantiation with Groovy map literal ([:] defaults to LinkedHashMap)."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
        override fun visitNewClass(newClass: J.NewClass, ctx: ExecutionContext): J {
            val nc = super.visitNewClass(newClass, ctx) as J.NewClass

            // Check if type is HashMap or LinkedHashMap
            // These are the specific types targeted by the rule, as they have direct literal equivalents in Groovy.
            val type = nc.clazz
            val simpleName = when (type) {
                is J.Identifier -> type.simpleName
                is J.FieldAccess -> type.name.simpleName
                is J.ParameterizedType -> {
                    val clazz = type.clazz
                    if (clazz is J.Identifier) {
                        clazz.simpleName
                    } else if (clazz is J.FieldAccess) {
                        clazz.name.simpleName
                    } else {
                        null
                    }
                }

                else -> null
            }

            val isTargetMap = simpleName == "HashMap" || simpleName == "LinkedHashMap"

            if (!isTargetMap) {
                return nc
            }

            // Check if arguments are empty (default constructor)
            val args = nc.arguments
            val isDefaultConstructor = args.isEmpty() || (args.size == 1 && args[0] is J.Empty)

            if (!isDefaultConstructor) {
                return nc
            }

            // Create [:] literal
            // Represented as G.MapLiteral with a single G.MapEntry containing J.Empty for both key and value.
            val emptyKey = J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY)
            val emptyValue = J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY)

            val emptyEntry = G.MapEntry(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(emptyKey),
                emptyValue,
                null,
            )

            return G.MapLiteral(
                Tree.randomId(),
                nc.prefix, // Preserve original whitespace/indentation
                Markers.EMPTY,
                JContainer.build(Collections.singletonList(JRightPadded.build(emptyEntry))),
                null, // Type will be inferred or null
            )
        }
    }
}
