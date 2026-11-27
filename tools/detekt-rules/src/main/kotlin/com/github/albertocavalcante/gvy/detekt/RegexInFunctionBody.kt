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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
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
 * `Pattern.compile(p, ...)` (also `java.util.regex.Pattern` and `kotlin.text.Regex`, read by PSI segment so that
 * backticks, comments and line breaks inside the qualifier do not hide them), where `p` is a constant, and
 * only when they sit lexically inside code that runs repeatedly: a function (named, local or anonymous), a property
 * accessor, a lambda that is not the direct argument of `lazy`, `run`, `let`, `also`, `apply`, `with`, `buildList`,
 * `buildMap`, `buildSet` or `buildString`, a secondary constructor or the `init` block of a class. A stored function
 * value (`val f = { Regex("a") }`) and a lambda for any other call (`map { ... }`) count as repeated.
 * Property initialisers at file, `object`, `companion object` and class scope are not flagged, nor is `object`
 * `init` (it runs once).
 *
 * A pattern is constant when it is a string literal without interpolation, such as `"a+"` or `"""a+"""`, or
 * concatenations, parentheses, `trimIndent()`/`trimMargin()` and `${NAME}` interpolations of constants. A name is
 * constant when this file declares it, at file or `object`/`companion object` scope, as a `const val` or a `val`
 * initialised with a string literal, no other declaration in the file shares the name, and no parameter, local,
 * loop, catch or destructuring name of that spelling is in scope at the use.
 *
 * Patterns built from a runtime value (a parameter, a call such as `Regex.escape(x)` or `Pattern.quote(x)`, an
 * interpolated expression, a `var`, or a name this file does not declare) cannot be hoisted, so they are not
 * flagged, and need no suppression. Only the pattern decides: `Regex.fromLiteral` and the option arguments are not
 * inspected. Without type resolution a name declared in another file counts as dynamic, a deliberate false negative.
 *
 * The documented limits of this syntactic approach are in the "Known limits" section of `tools/detekt-rules/README.md`.
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
        val qualified = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call }
        val receiver = qualified?.receiverExpression
        val first = call.firstArgument()
        // Null both without a receiver and for one that is not a dotted name, such as `factory().Regex(p)`.
        val qualifier = receiver?.qualifiedName()
        return when (name) {
            "Regex" -> constructionIf("Regex(...)", first, receiver == null || qualifier == "kotlin.text")
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
        is KtStringTemplateExpression -> expression.entries.all(::isConstantEntry)
        is KtParenthesizedExpression -> expression.expression?.let(::isConstant) ?: false
        is KtBinaryExpression ->
            expression.operationToken == KtTokens.PLUS &&
                listOf(expression.left, expression.right).all { it != null && isConstant(it) }
        is KtNameReferenceExpression -> expression.getReferencedName() in constantNames && !expression.isShadowed()
        is KtDotQualifiedExpression -> isTrimmedConstant(expression)
        else -> false
    }

    // KtStringTemplateEntry is not a sealed type, so the `when` needs its `else` branch.
    private fun isConstantEntry(entry: KtStringTemplateEntry): Boolean = when (entry) {
        is KtLiteralStringTemplateEntry, is KtEscapeStringTemplateEntry -> true
        is KtStringTemplateEntryWithExpression -> entry.expression?.let(::isConstant) ?: false
        else -> false
    }

    private fun isTrimmedConstant(expression: KtDotQualifiedExpression): Boolean {
        val selector = expression.selectorExpression as? KtCallExpression ?: return false
        val trimmer = (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        return trimmer in TRIMMERS && isConstant(expression.receiverExpression)
    }

    /**
     * Names this file declares as hoisted constants: a `const val`, or a `val` initialised with a plain string
     * literal, at file or `object`/`companion object` scope. A name that the file also declares as anything else
     * (a class member, a `var`, a computed value) is ambiguous without type resolution, so it is dropped.
     */
    private fun collectConstantNames(file: KtFile): Set<String> {
        val (constants, others) = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
            .filter { !it.isLocal }
            .partition { it.isHoistedConstant() }
        return constants.mapNotNull { it.name }.toSet() - others.mapNotNull { it.name }.toSet()
    }

    private fun KtProperty.isHoistedConstant(): Boolean {
        val owner = parent?.parent as? KtObjectDeclaration
        val atConstantScope = isTopLevel || (owner != null && !owner.isObjectLiteral())
        return !isVar && atConstantScope && (hasModifier(KtTokens.CONST_KEYWORD) || initializer.isPlainLiteral())
    }

    /** True when a parameter, local, loop, catch or destructuring name hides the file-level constant. */
    private fun KtNameReferenceExpression.isShadowed(): Boolean {
        val name = getReferencedName()
        return parents.any { scope -> name in scope.declaredNames() }
    }

    private fun PsiElement.declaredNames(): Set<String> = when (this) {
        is KtNamedFunction -> parameterNames(valueParameters)
        is KtFunctionLiteral -> parameterNames(valueParameters)
        is KtSecondaryConstructor -> parameterNames(valueParameters)
        is KtClassOrObject -> parameterNames(primaryConstructorParameters)
        is KtPropertyAccessor -> parameterNames(listOfNotNull(parameter))
        is KtForExpression -> parameterNames(listOfNotNull(loopParameter))
        is KtCatchClause -> parameterNames(listOfNotNull(catchParameter))
        is KtWhenExpression -> setOfNotNull(subjectVariable?.name)
        is KtBlockExpression -> statements.flatMap { statement ->
            when (statement) {
                is KtProperty -> listOfNotNull(statement.name)
                is KtDestructuringDeclaration -> statement.entries.mapNotNull { it.name }
                else -> emptyList()
            }
        }.toSet()
        else -> emptySet()
    }

    private fun parameterNames(parameters: List<KtParameter>): Set<String> = parameters.flatMap { parameter ->
        listOfNotNull(parameter.name) + parameter.destructuringDeclaration?.entries.orEmpty().mapNotNull { it.name }
    }.toSet()

    private fun KtExpression?.isPlainLiteral(): Boolean = this is KtStringTemplateExpression &&
        entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }

    private fun KtExpression.runsRepeatedly(): Boolean = parents.any { it.isRepeatedScope() }

    private fun PsiElement.isRepeatedScope(): Boolean = when (this) {
        is KtNamedFunction, is KtPropertyAccessor, is KtSecondaryConstructor -> true
        is KtAnonymousInitializer -> parent?.parent !is KtObjectDeclaration
        is KtFunctionLiteral -> !isRunOnceBuilder()
        else -> false
    }

    /**
     * A lambda handed straight to an inline scope function, builder or `lazy` runs where its call runs: once at
     * file, `object` and `companion object` scope, and per call inside a function. Either way the enclosing scopes
     * decide, so the lambda itself is not a repetition.
     */
    private fun KtFunctionLiteral.isRunOnceBuilder(): Boolean {
        val argument = (parent as? KtLambdaExpression)?.parent as? KtValueArgument ?: return false
        val owner = argument.parent.let { if (it is KtValueArgumentList) it.parent else it }
        val call = owner as? KtCallExpression
        return (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() in RUN_ONCE_CALLEES
    }

    private companion object {
        val REGEX_RECEIVERS = setOf("Regex", "kotlin.text.Regex")
        val PATTERN_RECEIVERS = setOf("Pattern", "java.util.regex.Pattern")
        val RUN_ONCE_CALLEES =
            setOf("lazy", "run", "let", "also", "apply", "with", "buildList", "buildMap", "buildSet", "buildString")
        val TRIMMERS = setOf("trimIndent", "trimMargin")
    }
}
