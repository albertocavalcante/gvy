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
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/** A PSI-only change detector, not a type resolver or a proof of wire reachability. */
internal class DirectCompilationCacheAccess(
    config: Config = Config.empty,
    private val exceptions: List<CacheAccessException> = CACHE_ACCESS_EXCEPTIONS,
) : Rule(config) {
    override val issue = Issue(
        id = "DirectCompilationCacheAccess",
        severity = Severity.Defect,
        description = "Review direct compilation cache and legacy entry access.",
        debt = Debt.FIVE_MINS,
    )

    private var inGls = false
    private var imports: Map<String, String> = emptyMap()
    private var receiverAliases: Set<String> = emptySet()
    private var unrelatedTypes: Map<String, String> = emptyMap()
    private val used = mutableMapOf<CacheAccessKey, Int>()

    override fun visitKtFile(file: KtFile) {
        inGls = file.packageFqName.asString().let { it == GLS_PACKAGE || it.startsWith("$GLS_PACKAGE.") }
        imports = file.importDirectives.mapNotNull { directive ->
            val alias = directive.aliasName ?: return@mapNotNull null
            val original = directive.importedFqName?.shortName()?.asString() ?: return@mapNotNull null
            alias to original
        }.toMap()
        val properties = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
        val parameters = PsiTreeUtil.findChildrenOfType(file, KtParameter::class.java)
        val declarations = properties.mapNotNull { it.name?.let { name -> name to it.typeReference?.text } } +
            parameters.mapNotNull { it.name?.let { name -> name to it.typeReference?.text } }
        unrelatedTypes = declarations.groupBy({ it.first }, { it.second }).mapNotNull { (name, types) ->
            types.singleOrNull()?.let { name to it }
        }.toMap()
        var names = KNOWN_RECEIVERS + declarations.filter { (_, type) ->
            type?.removeSuffix("?")?.substringAfterLast('.') in COMPILER_TYPES
        }.map { it.first }
        // Fixed point catches simple local aliases, without pretending to resolve arbitrary expressions.
        repeat(properties.size) {
            names = names + properties.filter { it.initializer?.text?.removePrefix("this.") in names }
                .mapNotNull { it.name }
        }
        receiverAliases = names
        used.clear()
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!inGls) return
        val name = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        val original = imports[name] ?: name
        val qualified = (expression.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == expression }
        val receiver = qualified?.receiverExpression?.text?.removePrefix("this.")
        val receiverType = receiver?.let { unrelatedTypes[it] }?.let { imports[it] ?: it }
        val unrelated = (receiverType to original) in UNRELATED_CALLS
        val accessor = original in ACCESSORS && !unrelated
        val acquisition = original in ACQUISITIONS && receiver in receiverAliases
        val legacy = original in LEGACY_ENTRIES
        if (accessor || acquisition || legacy) checkAccess(qualified ?: expression, original)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        if (!inGls) return
        val name = expression.callableReference.getReferencedName()
        val original = imports[name] ?: name
        if (original in ACCESSORS || original in ACQUISITIONS || original in LEGACY_ENTRIES) {
            checkAccess(expression, original)
        }
    }

    private fun checkAccess(element: PsiElement, name: String) {
        val function = element.parents.filterIsInstance<KtNamedFunction>().firstOrNull()
        val owner = element.parents.filterIsInstance<KtClassOrObject>().firstOrNull()?.name.orEmpty()
        val file = element.containingFile as? KtFile ?: return
        val key = CacheAccessKey(
            file.packageFqName.asString(), file.name, owner,
            function?.name.orEmpty(), function?.valueParameters?.map { it.typeReference?.text.orEmpty() }.orEmpty(),
            element.text,
        )
        val count = (used[key] ?: 0) + 1
        used[key] = count
        if (!exceptions.allows(key, count)) {
            report(CodeSmell(issue, Entity.from(element), "Review direct cache or legacy access: $name"))
        }
    }

    private companion object {
        const val GLS_PACKAGE = "com.github.albertocavalcante.gvy.gls"
        val KNOWN_RECEIVERS = setOf("compilationService", "compilation", "parseAccessor")
        val DISTINCT_ACCESSORS = setOf("getCompiledSnapshot", "compiledTextOf", "createContextWithSource")
        val ACCESSORS = DISTINCT_ACCESSORS + setOf(
            "getAst", "getParseResult", "getAstModel", "getSymbolTable", "getDiagnostics", "getTokenIndex",
            "createContext",
        )
        val COMPILER_TYPES = setOf("GroovyCompilationService", "ParseResultAccessor")
        val UNRELATED_CALLS = setOf("RuleContext" to "getAst", "DiagnosticsService" to "getDiagnostics")
        val ACQUISITIONS = setOf("compile", "ensureCompiled")
        val LEGACY_ENTRIES = setOf(
            "provideHover", "provideSignatureHelp", "getContextualCompletions", "provideCodeActions",
            "provideReferences",
        )
    }
}
