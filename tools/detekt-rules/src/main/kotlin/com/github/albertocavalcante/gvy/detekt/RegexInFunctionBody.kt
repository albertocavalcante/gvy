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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Reports a regex that is compiled again every time the enclosing code runs although its pattern is a constant.
 *
 * Compiling a pattern is far more expensive than matching with it, so a constant pattern belongs in a file-scope,
 * `object` or `companion object` property (or behind `by lazy`), compiled once.
 *
 * The rule is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409).
 *
 * Flagged constructions: `Regex(p)`, `Regex.fromLiteral(p)`, `p.toRegex(...)`, `p.toPattern(...)` and
 * `Pattern.compile(p, ...)` (also `java.util.regex.Pattern` and `kotlin.text.Regex`), where `p` is a constant, and
 * only when they sit lexically inside code that runs repeatedly: a function (named, local or anonymous), a property
 * accessor, a lambda that is not the argument of `lazy`, a secondary constructor or the `init` block of a class.
 * Property initialisers at file, `object`, `companion object` and class scope are not flagged, nor is `object`
 * `init` (it runs once).
 *
 * A pattern is constant when it is a string literal without interpolation, such as `"a+"` or `"""a+"""`, or
 * concatenations, parentheses, `trimIndent()`/`trimMargin()` and `${NAME}` interpolations of constants. A name is
 * constant when this file declares it as a `const val`, or as a non-local `val` initialised with a string literal.
 *
 * Patterns built from a runtime value (a parameter, a call such as `Regex.escape(x)` or `Pattern.quote(x)`, an
 * interpolated expression, a `var`, or a name this file does not declare) cannot be hoisted, so they are not
 * flagged, and need no suppression. Only the pattern decides: `Regex.fromLiteral` and the option arguments are not
 * inspected. Without type resolution a name declared in another file counts as dynamic, a deliberate false negative.
 */
internal class RegexInFunctionBody(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "RegexInFunctionBody",
        severity = Severity.Performance,
        description = "A constant regex is compiled on every call. Hoist it into a file-scope, object or " +
            "companion property (or behind `by lazy`), or use a plain string search.",
        debt = Debt.FIVE_MINS,
    )

    private var constantNames: Set<String> = emptySet()

    override fun visitKtFile(file: KtFile) {
        constantNames = collectConstantNames(file)
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val construction = constructionOf(expression) ?: return
        if (isConstant(construction.pattern) && expression.runsRepeatedly()) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "`${construction.description}` compiles a constant regex on every call: hoist it into a " +
                        "file-scope, object or companion property (or `by lazy`), or use a plain string search.",
                ),
            )
        }
    }

    private class Construction(val description: String, val pattern: KtExpression)

    private fun constructionOf(call: KtCallExpression): Construction? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val qualified = (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == call }
        val receiver = qualified?.receiverExpression
        val first = call.firstArgument()
        val qualifier = receiver?.text
        return when (name) {
            "Regex" -> constructionIf("Regex(...)", first, qualifier == null || qualifier == "kotlin.text")
            "fromLiteral" -> constructionIf("Regex.fromLiteral(...)", first, qualifier in REGEX_RECEIVERS)
            "compile" -> constructionIf("Pattern.compile(...)", first, qualifier in PATTERN_RECEIVERS)
            "toRegex", "toPattern" -> receiver?.let { Construction("$name()", it) }
            else -> null
        }
    }

    private fun constructionIf(description: String, pattern: KtExpression?, applies: Boolean): Construction? =
        if (applies && pattern != null) Construction(description, pattern) else null

    private fun KtCallExpression.firstArgument(): KtExpression? = valueArguments.firstOrNull()?.getArgumentExpression()

    private fun isConstant(expression: KtExpression): Boolean = when (expression) {
        is KtStringTemplateExpression -> expression.entries.all { entry ->
            when (entry) {
                is KtLiteralStringTemplateEntry, is KtEscapeStringTemplateEntry -> true
                is KtSimpleNameStringTemplateEntry, is KtStringTemplateEntryWithExpression ->
                    entry.expression?.let(::isConstant) ?: false
                else -> false
            }
        }
        is KtParenthesizedExpression -> expression.expression?.let(::isConstant) ?: false
        is KtBinaryExpression ->
            expression.operationToken == KtTokens.PLUS &&
                listOf(expression.left, expression.right).all { it != null && isConstant(it) }
        is KtNameReferenceExpression -> expression.getReferencedName() in constantNames
        is KtDotQualifiedExpression -> isTrimmedConstant(expression)
        else -> false
    }

    private fun isTrimmedConstant(expression: KtDotQualifiedExpression): Boolean {
        val selector = expression.selectorExpression as? KtCallExpression ?: return false
        val trimmer = (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        return trimmer in TRIMMERS && isConstant(expression.receiverExpression)
    }

    private fun collectConstantNames(file: KtFile): Set<String> {
        val properties = generateSequence(listOf<PsiElement>(file)) { level -> level.flatMap { it.children.toList() } }
            .takeWhile { it.isNotEmpty() }
            .flatten()
            .filterIsInstance<KtProperty>()
            .filter { !it.isLocal && !it.isVar }
        return properties.filter { it.hasModifier(KtTokens.CONST_KEYWORD) || it.initializer.isPlainLiteral() }
            .mapNotNull { it.name }
            .toSet()
    }

    private fun KtExpression?.isPlainLiteral(): Boolean = this is KtStringTemplateExpression &&
        entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }

    private fun KtExpression.runsRepeatedly(): Boolean = parents.any { it.isRepeatedScope() }

    private fun PsiElement.isRepeatedScope(): Boolean = when (this) {
        is KtNamedFunction, is KtPropertyAccessor, is KtSecondaryConstructor -> true
        is KtAnonymousInitializer -> parent?.parent !is KtObjectDeclaration
        is KtFunctionLiteral -> !isLazyInitializer()
        else -> false
    }

    /** A lambda handed to `lazy` runs at most once per property, so the enclosing scope alone decides. */
    private fun KtFunctionLiteral.isLazyInitializer(): Boolean {
        val argument = (parent as? KtLambdaExpression)?.parent as? KtValueArgument ?: return false
        val owner = argument.parent.let { if (it is KtValueArgumentList) it.parent else it }
        val call = owner as? KtCallExpression
        return (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "lazy"
    }

    private companion object {
        val REGEX_RECEIVERS = setOf("Regex", "kotlin.text.Regex")
        val PATTERN_RECEIVERS = setOf("Pattern", "java.util.regex.Pattern")
        val TRIMMERS = setOf("trimIndent", "trimMargin")
    }
}
