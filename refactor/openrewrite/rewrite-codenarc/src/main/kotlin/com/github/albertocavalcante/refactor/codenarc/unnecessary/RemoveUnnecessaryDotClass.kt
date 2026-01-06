package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to remove unnecessary .class references in Groovy.
 *
 * This aligns with CodeNarc rule: UnnecessaryDotClass
 *
 * In Groovy, referencing the class name directly is equivalent to .class
 *
 * TODO: Support fully qualified type names (e.g. java.lang.String.class).
 * Currently only matches simple identifiers.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydotclass">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDotClass : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary .class"

    override fun getDescription(): String =
        "Removes unnecessary .class from type references (e.g. String.class -> String)."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
        override fun visitFieldAccess(fieldAccess: J.FieldAccess, ctx: ExecutionContext): J {
            val fa = super.visitFieldAccess(fieldAccess, ctx) as J.FieldAccess

            // Check if we are accessing ".class" on a type identifier
            val target = fa.target
            if (fa.name.simpleName == "class" &&
                target is J.Identifier &&
                target.simpleName.firstOrNull()?.isUpperCase() == true
            ) {
                // Return the target identifier, preserving prefix for formatting
                return target.withPrefix(fieldAccess.prefix)
            }

            return fa
        }
    }
}
