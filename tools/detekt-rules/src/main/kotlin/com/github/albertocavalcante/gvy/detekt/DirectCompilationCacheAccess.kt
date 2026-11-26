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
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A PSI-only change detector for direct GLS cache reads, acquisitions and legacy entry points. */
internal class DirectCompilationCacheAccess(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "DirectCompilationCacheAccess",
        severity = Severity.Defect,
        description = "Review direct compilation cache and legacy entry access.",
        debt = Debt.FIVE_MINS,
    )

    private lateinit var receivers: CacheReceiverLookup
    private var compilerFile: String? = null
    private val used = mutableMapOf<CacheAccessKey, Int>()

    override fun visitKtFile(file: KtFile) {
        val pkg = file.packageFqName.asString()
        if (pkg != GLS_PACKAGE && !pkg.startsWith("$GLS_PACKAGE.")) return

        receivers = CacheReceiverLookup(file)
        compilerFile = compilerForwarderFile(file)
        used.clear()
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        val qualified = (expression.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == expression }
        checkAccess(qualified ?: expression, name, qualified?.receiverExpression, expression.valueArguments.size)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        checkAccess(expression, expression.callableReference.getReferencedName(), expression.receiverExpression, null)
    }

    private fun checkAccess(element: KtExpression, writtenName: String, receiver: KtExpression?, argumentCount: Int?) {
        val name = when {
            writtenName in GUARDED_NAMES -> writtenName
            receivers.imports[writtenName] == "$GLS_PACKAGE.converters.compiledTextOf" -> "compiledTextOf"
            else -> return
        }
        val requiredType = REQUIRED_RECEIVERS[name]
        if (requiredType != null && receivers.typeOf(receiver, element) != requiredType) return
        val unrelated = UNRELATED_GETTERS[name]
        if (unrelated != null && (argumentCount == null || argumentCount == unrelated.second)) {
            if (receivers.typeOf(receiver, element) == unrelated.first) return
        }

        val key = compilerFile?.let { forwarderKey(it, element, receivers) }
        if (key != null) {
            val occurrence = (used[key] ?: 0) + 1
            used[key] = occurrence
            if (CACHE_ACCESS_EXCEPTIONS.allows(key, occurrence)) return
        }
        report(CodeSmell(issue, Entity.from(element), "Review direct cache or legacy access: $name"))
    }

    private companion object {
        val REQUIRED_RECEIVERS = mapOf(
            "compile" to COMPILER_TYPE,
            "compileAsync" to COMPILER_TYPE,
            "ensureCompiled" to COMPILER_TYPE,
            "getSession" to COMPILER_TYPE,
            "getCached" to CACHE_SERVICE_TYPE,
        )
        val UNRELATED_GETTERS = mapOf(
            "getAst" to ("$GLS_PACKAGE.providers.diagnostics.rules.RuleContext" to 0),
            "getDiagnostics" to ("$GLS_PACKAGE.services.DiagnosticsService" to 2),
        )
        val GUARDED_NAMES = REQUIRED_RECEIVERS.keys + setOf(
            "getCompiledSnapshot", "getCompiledContent", "getValidParseResult", "getCachedWithContent",
            "compiledTextOf", "createContextWithSource", "getAst", "getParseResult", "getAstModel",
            "getSymbolTable", "getDiagnostics", "getTokenIndex", "createContext",
            "provideHover", "provideSignatureHelp", "getContextualCompletions", "provideCodeActions",
            "provideReferences",
        )
    }
}
