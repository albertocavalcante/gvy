// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports uses of `java.util.concurrent.atomic`: an import, or a qualified name such as
 * `java.util.concurrent.atomic.AtomicLong()` in an expression or a type.
 *
 * Shared state goes through gvy's own `OnceFlag`, `SharedValue` and `Counter` from `:common:concurrent`, which keep the
 * JVM's atomics in one module (#2474). Test sources, the facade itself and modules published on their own are
 * excluded in `tools/lint/detekt.yml`.
 *
 * The rule is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409). A name imported from the package and used unqualified is reported at its import, once. A sanctioned use
 * (a field updater) writes the qualified name at a declaration annotated `@Suppress("JdkAtomics")`: an import can only
 * be silenced for the whole file.
 */
internal class JdkAtomics(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "JdkAtomics",
        severity = Severity.Maintainability,
        description = "Shared state uses java.util.concurrent.atomic directly. Use OnceFlag, SharedValue or Counter " +
            "from :common:concurrent (#2474).",
        debt = Debt.TEN_MINS,
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (inPackage(imported)) reportAt(importDirective, importDirective.importPath?.pathStr ?: imported)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val selector = expression.selectorExpression
        if (selector == null || expression.receiverExpression.qualifiedName() != PACKAGE) return
        // An import's path is itself a qualified expression; the import directive is reported instead.
        if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
        // Name the type, not the whole call: `AtomicLong`, not `AtomicLong(initial)`.
        val type = (selector as? KtCallExpression)?.calleeExpression ?: selector
        reportAt(expression, "$PACKAGE.${type.qualifiedName() ?: type.text.filterNot(Char::isWhitespace)}")
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.qualifier?.qualifiedName() == PACKAGE) reportAt(type, "$PACKAGE.${type.referencedName}")
    }

    private fun reportAt(element: PsiElement, name: String) {
        report(
            CodeSmell(
                issue,
                Entity.from(element),
                "`$name`: use OnceFlag, SharedValue or Counter from :common:concurrent instead of the JDK atomics, " +
                    "or a plain field for state one thread owns (#2474).",
            ),
        )
    }

    private fun inPackage(name: String): Boolean = name == PACKAGE || name.startsWith("$PACKAGE.")

    // Names come from PSI segments, so whitespace, comments and backticks between them do not matter.
    private fun KtExpression.qualifiedName(): String? = when (this) {
        is KtNameReferenceExpression -> getReferencedName()
        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression.qualifiedName()
            val selector = selectorExpression?.qualifiedName()
            if (receiver != null && selector != null) "$receiver.$selector" else null
        }
        else -> null
    }

    private fun KtUserType.qualifiedName(): String? {
        val name = referencedName ?: return null
        val outer = qualifier ?: return name
        return outer.qualifiedName()?.let { "$it.$name" }
    }

    private companion object {
        const val PACKAGE = "java.util.concurrent.atomic"
    }
}
