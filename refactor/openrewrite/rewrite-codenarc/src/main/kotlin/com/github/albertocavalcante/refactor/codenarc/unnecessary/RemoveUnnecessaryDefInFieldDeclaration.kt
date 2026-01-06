package com.github.albertocavalcante.refactor.codenarc.unnecessary

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to remove unnecessary def in field declarations.
 *
 * In Groovy, def is redundant when combined with modifiers or explicit types.
 *
 * This aligns with CodeNarc rule: UnnecessaryDefInFieldDeclaration
 *
 * @see <a href="https://codenarc.org/codenarc-rules-unnecessary.html#unnecessarydefinfielddeclaration">CodeNarc Rule</a>
 */
class RemoveUnnecessaryDefInFieldDeclaration : Recipe() {

    override fun getDisplayName(): String = "Remove unnecessary def in field declaration"

    override fun getDescription(): String =
        "Removes unnecessary def keyword from field declarations that have modifiers or explicit types."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {

        override fun visitVariableDeclarations(
            multiVariable: J.VariableDeclarations,
            ctx: ExecutionContext,
        ): J.VariableDeclarations {
            var v = super.visitVariableDeclarations(multiVariable, ctx)

            // In OpenRewrite, Groovy's 'def' is parsed as J.Modifier.Type.LanguageExtension
            val defModifierIndex = v.modifiers.indexOfFirst { isDefModifier(it) }

            if (defModifierIndex < 0) {
                return v // No def keyword present
            }

            // Check if there are other modifiers besides def
            val otherModifiers = v.modifiers.filterIndexed { i, _ -> i != defModifierIndex }
            val hasOtherModifiers = otherModifiers.isNotEmpty()

            // Check if there's an explicit type (not just def)
            val hasExplicitType = v.typeExpression != null

            // Remove def if there are other modifiers or an explicit type
            if (hasOtherModifiers || hasExplicitType) {
                val defModifier = v.modifiers[defModifierIndex]
                val newModifiers = otherModifiers.toMutableList()

                // Transfer def's prefix to the first remaining modifier or type expression
                if (newModifiers.isNotEmpty()) {
                    newModifiers[0] = newModifiers[0].withPrefix(defModifier.prefix)
                } else if (v.typeExpression != null) {
                    // No modifiers left, transfer prefix to type expression
                    v = v.withTypeExpression(v.typeExpression!!.withPrefix(defModifier.prefix))
                }

                v = v.withModifiers(newModifiers)
            }

            return v
        }

        private fun isDefModifier(modifier: J.Modifier): Boolean {
            // In OpenRewrite 8+, def is Type.LanguageExtension with keyword "def"
            // The modifier's type might be LanguageExtension, or we check the keyword text
            return modifier.type == J.Modifier.Type.LanguageExtension &&
                modifier.keyword?.lowercase() == "def"
        }
    }
}
