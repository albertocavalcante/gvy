package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space

/**
 * Recipe to remove unnecessary public modifiers in Groovy.
 *
 * In Groovy, classes, methods, and constructors are public by default.
 *
 * This aligns with CodeNarc rule: UnnecessaryPublicModifier
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarypublicmodifier">CodeNarc Rule</a>
 */
class RemoveUnnecessaryPublicModifier : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary public modifier"

    override fun getDescription(): String =
        "Removes unnecessary public modifiers from classes, methods, and constructors in Groovy."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitClassDeclaration(classDecl: J.ClassDeclaration, ctx: ExecutionContext): J.ClassDeclaration {
            var cd = super.visitClassDeclaration(classDecl, ctx)

            val publicIndex = cd.modifiers.indexOfFirst { it.type == J.Modifier.Type.Public }
            if (publicIndex >= 0) {
                val publicModifier = cd.modifiers[publicIndex]
                val newModifiers = cd.modifiers.filterIndexed { i, _ -> i != publicIndex }.toMutableList()

                if (newModifiers.isNotEmpty()) {
                    // Give public's prefix to the first remaining modifier
                    newModifiers[0] = newModifiers[0].withPrefix(publicModifier.prefix)
                } else {
                    // No modifiers left - adjust the Kind's prefix to use public's prefix
                    // The Kind (class, interface, etc.) comes right after modifiers
                    val padding = cd.padding
                    val kind = padding.kind
                    cd = cd.padding.withKind(kind.withPrefix(publicModifier.prefix))
                }
                cd = cd.withModifiers(newModifiers)
            }
            return cd
        }

        override fun visitMethodDeclaration(method: J.MethodDeclaration, ctx: ExecutionContext): J.MethodDeclaration {
            var md = super.visitMethodDeclaration(method, ctx)

            val publicIndex = md.modifiers.indexOfFirst { it.type == J.Modifier.Type.Public }
            if (publicIndex >= 0) {
                val publicModifier = md.modifiers[publicIndex]
                val newModifiers = md.modifiers.filterIndexed { i, _ -> i != publicIndex }.toMutableList()

                if (newModifiers.isNotEmpty()) {
                    // Give public's prefix to the first remaining modifier
                    newModifiers[0] = newModifiers[0].withPrefix(publicModifier.prefix)
                } else {
                    // No modifiers left - we need to adjust the return type or method name prefix
                    // The return type (or method name for constructors) comes after modifiers
                    md = md.withReturnTypeExpression(
                        md.returnTypeExpression?.withPrefix(publicModifier.prefix),
                    )
                    // For constructors without return type, adjust the name
                    if (md.returnTypeExpression == null) {
                        md = md.withName(md.name.withPrefix(publicModifier.prefix))
                    }
                }
                md = md.withModifiers(newModifiers)
            }
            return md
        }
    }
}
