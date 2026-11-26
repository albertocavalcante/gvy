// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

internal const val GLS_PACKAGE = "com.github.albertocavalcante.gvy.gls"
private const val COMPILER_TYPE = "$GLS_PACKAGE.compilation.GroovyCompilationService"
private val COMPILER_NAMES = setOf("compilationService", "compilation")

/** Looks outward from the use. A nearer untyped binding stops lookup just as a typed binding does. */
internal class CacheReceiverLookup(private val file: KtFile) {
    val imports = file.importDirectives.filterNot { it.isAllUnder }.mapNotNull { directive ->
        val target = directive.importedFqName ?: return@mapNotNull null
        (directive.aliasName ?: target.shortName().asString()) to target.asString()
    }.toMap()

    fun isCompiler(receiver: KtExpression?, use: PsiElement): Boolean = typeOf(receiver, use) == COMPILER_TYPE

    fun typeOf(receiver: KtExpression?, use: PsiElement): String? = when (receiver) {
        null -> implicitReceiver(use)
        is KtThisExpression -> thisType(receiver)
        else -> expressionType(receiver, mutableSetOf())
    }

    fun declaredType(type: KtTypeReference?): String? {
        val name = type?.text?.removeSuffix("?") ?: return null
        return qualify(name, type)
    }

    private fun qualify(name: String, use: PsiElement): String? {
        if ('.' in name) return name
        // A type parameter, nested class or local type alias may shadow even an explicit import.
        if (use.parents.any { it.shadowsType(name) }) return null
        return imports[name] ?: "${file.packageFqName.asString()}.$name"
    }

    private fun PsiElement.shadowsType(name: String): Boolean {
        if (this is KtTypeParameterListOwner && typeParameters.any { it.name == name }) return true
        val declarations = when (this) {
            is KtBlockExpression -> statements.filterIsInstance<KtDeclaration>()
            is KtDeclarationContainer -> this.declarations
            else -> emptyList()
        }
        return declarations.any {
            (it is KtClassOrObject || it is KtTypeAlias) && (it as KtNamedDeclaration).name == name &&
                (it.parent !is KtFile || it is KtTypeAlias || name in imports)
        }
    }

    private fun implicitReceiver(use: PsiElement): String? {
        for (scope in use.parents) {
            // A lambda can introduce a receiver whose type ordinary Detekt cannot determine.
            if (scope is KtFunctionLiteral) return null
            if (scope is KtFunction && scope.receiverTypeReference != null) {
                return declaredType(scope.receiverTypeReference)
            }
            if (scope is KtClassOrObject) return scope.fqName?.asString()
        }
        return null
    }

    private fun thisType(expression: KtThisExpression): String? {
        val label = expression.getLabelName() ?: return implicitReceiver(expression)
        val scope = expression.parents.filterIsInstance<KtNamedDeclaration>().firstOrNull { it.name == label }
        return when (scope) {
            is KtFunction -> declaredType(scope.receiverTypeReference)
            is KtClassOrObject -> scope.fqName?.asString()
            else -> null
        }
    }

    private fun expressionType(expression: KtExpression?, seen: MutableSet<KtProperty>): String? = when (expression) {
        is KtNameReferenceExpression -> nameType(expression, seen)
        is KtParenthesizedExpression -> expressionType(expression.expression, seen)
        is KtThisExpression -> thisType(expression)
        is KtQualifiedExpression -> qualifiedType(expression, seen)
        is KtCallExpression -> expression.calleeExpression?.text?.let { qualify(it, expression) }
        else -> null
    }

    private fun nameType(use: KtNameReferenceExpression, seen: MutableSet<KtProperty>): String? {
        val name = use.getReferencedName()
        for (scope in use.parents) {
            val binding = scope.binding(name, use)
            if (binding != null) return bindingType(binding, seen)
            if (scope is KtFunctionLiteral && name == "it" && scope.valueParameterList == null) return null
            // An inherited property can hide an outer or file-scope property of the same name.
            if (scope is KtClassOrObject) break
        }
        return if (name in COMPILER_NAMES) COMPILER_TYPE else qualify(name, use)
    }

    private fun qualifiedType(expression: KtQualifiedExpression, seen: MutableSet<KtProperty>): String? {
        val name = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val receiver = expression.receiverExpression
        if (receiver is KtThisExpression) {
            val thisType = thisType(receiver)
            val owner = expression.parents.filterIsInstance<KtClassOrObject>().firstOrNull { it.fqName != null }
            if (owner != null && owner.fqName?.asString() == thisType) {
                owner.binding(name, expression)?.let { return bindingType(it, seen) }
            }
        }
        return if (name in COMPILER_NAMES) COMPILER_TYPE else expression.text
    }

    private fun bindingType(binding: KtNamedDeclaration, seen: MutableSet<KtProperty>): String? = when (binding) {
        is KtParameter -> declaredType(binding.typeReference)
        is KtProperty -> when {
            binding.typeReference != null -> declaredType(binding.typeReference)
            binding.isVar || !seen.add(binding) -> null
            else -> expressionType(binding.initializer, seen)
        }
        else -> null
    }

    private fun PsiElement.binding(name: String, use: PsiElement): KtNamedDeclaration? {
        val candidates = when (this) {
            is KtBlockExpression -> statements.asReversed().filter { it.textRange.endOffset <= use.textOffset }
            is KtClassOrObject -> declarations + primaryConstructorParameters.filter { it.hasValOrVar() }
            is KtFile -> declarations
            is KtFunction -> valueParameters
            is KtPropertyAccessor -> listOfNotNull(parameter)
            is KtForExpression -> listOfNotNull(destructuringDeclaration, loopParameter)
            is KtCatchClause -> listOfNotNull(catchParameter)
            is KtWhenExpression -> listOfNotNull(subjectVariable)
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull { it.namedBinding(name) }
    }

    private fun PsiElement.namedBinding(name: String): KtNamedDeclaration? = when (this) {
        is KtDestructuringDeclaration -> entries.firstOrNull { it.name == name }
        is KtParameter -> destructuringDeclaration?.entries?.firstOrNull { it.name == name }
            ?: takeIf { it.name == name }
        is KtProperty -> takeIf { it.name == name }
        else -> null
    }
}
