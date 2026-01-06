package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.marker.AsStyleTypeCast
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JContainer
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.JavaType
import org.openrewrite.java.tree.Space
import org.openrewrite.java.tree.TypeTree
import org.openrewrite.marker.Markers
import java.util.Collections

/**
 * Recipe to simplify explicit HashSet instantiation to `[] as Set`.
 *
 * This aligns with CodeNarc rule: ExplicitHashSetInstantiation
 *
 * e.g. new HashSet() -> [] as Set
 */
class SimplifyExplicitHashSetInstantiation : Recipe() {

    override fun getDisplayName(): String = "Simplify explicit HashSet instantiation"

    override fun getDescription(): String = "Replaces explicit HashSet instantiation with `[] as Set`."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
        override fun visitNewClass(newClass: J.NewClass, ctx: ExecutionContext): J {
            val nc = super.visitNewClass(newClass, ctx) as J.NewClass

            // Check if type is HashSet
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

            val isHashSet = simpleName == "HashSet"

            if (!isHashSet) {
                return nc
            }

            // Check if arguments are empty (default constructor)
            val args = nc.arguments
            val isDefaultConstructor = args.isEmpty() || (args.size == 1 && args[0] is J.Empty)

            if (!isDefaultConstructor) {
                return nc
            }

            // Create [] literal
            val listLiteral = G.ListLiteral(
                Tree.randomId(),
                nc.prefix,
                Markers.EMPTY,
                JContainer.build(Collections.emptyList<JRightPadded<Expression>>()),
                null,
            )

            // Create "Set" type identifier
            val setType = J.Identifier(
                Tree.randomId(),
                Space.format(" "), // Space before "Set" (after "as")
                Markers.EMPTY,
                emptyList(),
                "Set",
                null,
                null,
            )

            // Wrap in ControlParentheses (required structure for J.TypeCast)
            // GroovyPrinter uses the 'after' space of the tree element for the space before "as"
            val controlParens = J.ControlParentheses<TypeTree>(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(setType as TypeTree).withAfter(Space.format(" ")),
            )

            // Create TypeCast with AsStyleTypeCast marker
            // Structure: expression as clazz
            // listLiteral as Set
            // Note: The "as" keyword is implied by the marker
            // The space around "as" is handled by the printer or needs prefix on clazz?
            // GroovyPrinter: p.append("as"); visit(t.getClazz().getTree(), p);
            // It uses Space.Location.CONTROL_PARENTHESES_PREFIX for space before "as"?
            // visitSpace(t.getClazz().getPadding().getTree().getAfter(), Space.Location.CONTROL_PARENTHESES_PREFIX, p);
            // Wait, getAfter() of tree padding?
            // Let's use default spacing and see. Ideally, we want " [] as Set".
            // The " as " spacing might need adjustment.

            return J.TypeCast(
                Tree.randomId(),
                Space.EMPTY, // Prefix handled by listLiteral (transferred from newClass)
                Markers.EMPTY.addIfAbsent(AsStyleTypeCast(Tree.randomId())),
                controlParens,
                listLiteral,
            )
        }
    }
}
