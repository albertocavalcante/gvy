// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports every use of the package [packageName]: an import of the package or of a name in it, a star import
 * included, and a qualified name such as `pkg.Type()` in an expression or `pkg.Type` in a type or an annotation.
 *
 * The detection is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type
 * resolution (#2409). Names are read by PSI segment ([qualifiedName]), so backticks, comments and line breaks inside a
 * name do not hide it, and a longer package such as `pkg.other` is not [packageName]. A name imported from the package
 * and used unqualified is reported at its import, once. A sanctioned use writes the qualified name at a declaration
 * annotated `@Suppress` with the rule id: an import can only be silenced for the whole file.
 */
internal abstract class PackageUseRule(config: Config, private val packageName: String) : Rule(config) {
    /** The finding's message for a use of [name], the fully qualified name the code spells or imports. */
    protected abstract fun message(name: String): String

    override fun visitImportDirective(importDirective: KtImportDirective) {
        // No `super` call: the import's path is itself a qualified expression, and the import is reported here, once.
        val imported = importDirective.importedFqName?.asString() ?: return
        if (imported == packageName || imported.startsWith("$packageName.")) {
            reportAt(importDirective, importDirective.importPath?.pathStr ?: imported)
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.receiverExpression.qualifiedName() != packageName) return
        // Name the type, not the whole call: `AtomicLong`, not `AtomicLong(initial)`.
        val selector = expression.selectorExpression
        val type = ((selector as? KtCallExpression)?.calleeExpression ?: selector)?.qualifiedName() ?: return
        reportAt(expression, "$packageName.$type")
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.qualifier?.qualifiedName() == packageName) reportAt(type, "$packageName.${type.referencedName}")
    }

    private fun reportAt(element: PsiElement, name: String) {
        report(CodeSmell(issue, Entity.from(element), message(name)))
    }
}
