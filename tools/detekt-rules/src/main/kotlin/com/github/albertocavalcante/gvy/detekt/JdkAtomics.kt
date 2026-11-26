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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
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
 * (#2409). A name imported from the package and used unqualified is reported at its import, once.
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
        if (inPackage(imported)) reportAt(importDirective, imported)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        // An import's path is itself a qualified expression; the import directive is reported instead.
        if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
        if (expression.receiverExpression.text.withoutWhitespace() == PACKAGE) reportAt(expression, expression.text)
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (PsiTreeUtil.getParentOfType(type, KtImportDirective::class.java) != null) return
        if (type.qualifier?.text?.withoutWhitespace() == PACKAGE) reportAt(type, type.text)
    }

    private fun reportAt(element: PsiElement, spelling: String) {
        report(
            CodeSmell(
                issue,
                Entity.from(element),
                "`${spelling.withoutWhitespace()}` uses java.util.concurrent.atomic directly. Use OnceFlag, " +
                    "SharedValue or Counter from :common:concurrent (#2474).",
            ),
        )
    }

    private fun inPackage(name: String): Boolean = name == PACKAGE || name.startsWith("$PACKAGE.")

    private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)

    private companion object {
        const val PACKAGE = "java.util.concurrent.atomic"
    }
}
