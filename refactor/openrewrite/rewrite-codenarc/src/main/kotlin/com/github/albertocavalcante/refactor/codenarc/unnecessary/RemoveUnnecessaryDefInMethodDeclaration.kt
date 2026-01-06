package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to remove unnecessary def in method declarations.
 *
 * In Groovy, def is redundant when combined with modifiers or explicit return types.
 *
 * This aligns with CodeNarc rule: UnnecessaryDefInMethodDeclaration
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydefinmethoddeclaration">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDefInMethodDeclaration : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary def in method declaration"

    override fun getDescription(): String =
        "Removes unnecessary def keyword from method declarations that have modifiers or explicit return types."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitMethodDeclaration(method: J.MethodDeclaration, ctx: ExecutionContext): J.MethodDeclaration {
            var md = super.visitMethodDeclaration(method, ctx)

            // In OpenRewrite, Groovy's 'def' is parsed as J.Modifier.Type.LanguageExtension
            val defModifierIndex = md.modifiers.indexOfFirst { isDefModifier(it) }

            if (defModifierIndex < 0) {
                return md // No def keyword present
            }

            // Check if there are other modifiers besides def
            val otherModifiers = md.modifiers.filterIndexed { i, _ -> i != defModifierIndex }
            val hasOtherModifiers = otherModifiers.isNotEmpty()

            // Check if there's an explicit return type (not just def)
            val hasExplicitReturnType = md.returnTypeExpression != null

            // Remove def if there are other modifiers or an explicit return type
            if (hasOtherModifiers || hasExplicitReturnType) {
                val defModifier = md.modifiers[defModifierIndex]
                val newModifiers = otherModifiers.toMutableList()

                // Transfer def's prefix to the first remaining modifier or return type
                if (newModifiers.isNotEmpty()) {
                    newModifiers[0] = newModifiers[0].withPrefix(defModifier.prefix)
                } else if (md.returnTypeExpression != null) {
                    // No modifiers left, transfer prefix to return type expression
                    md = md.withReturnTypeExpression(md.returnTypeExpression!!.withPrefix(defModifier.prefix))
                }

                md = md.withModifiers(newModifiers)
            }

            return md
        }

        private fun isDefModifier(modifier: J.Modifier): Boolean = modifier.type == J.Modifier.Type.LanguageExtension &&
            modifier.keyword?.lowercase() == "def"
    }
}
