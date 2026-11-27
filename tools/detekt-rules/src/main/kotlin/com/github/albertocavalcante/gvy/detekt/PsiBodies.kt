// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

// The shape of a function body, as gvy's test function rules read it (#2507): whether it follows `=`, and whether it
// is one coroutine builder. Read from PSI, never from source text, like the dotted names in PsiNames.kt.

/** True when the function's body follows `=`: `fun f() = g()`. False for a block body and for no body at all. */
internal fun KtNamedFunction.hasExpressionBody(): Boolean = equalsToken != null

/**
 * The name this expression calls, without its receiver or arguments: `runBlocking` for `runBlocking { }`,
 * `kotlinx.coroutines.runBlocking { }` and `scope.runTest { }`. Null when the expression is not a call of a plain name.
 */
internal fun KtExpression.calledName(): String? = (call()?.calleeExpression as? KtNameReferenceExpression)
    ?.getReferencedName()

/**
 * This expression as a call, looking through one receiver: `f()` and `a.f()` are both the call of `f`. A safe call
 * (`a?.f()`) is not looked through: its value is nullable, so it cannot be the `Unit` body of a function.
 */
internal fun KtExpression.call(): KtCallExpression? = when (this) {
    is KtCallExpression -> this
    is KtDotQualifiedExpression -> selectorExpression as? KtCallExpression
    else -> null
}

/**
 * This expression as a call of a coroutine builder, or null when it is anything else. A builder is a call named
 * `runBlocking` or `runTest` with a trailing lambda, with or without a receiver or arguments.
 */
internal fun KtExpression.builderCall(): KtCallExpression? =
    call()?.takeIf { it.calledName() in COROUTINE_BUILDERS && it.lambdaArguments.isNotEmpty() }

/**
 * The statement that is the whole block body of this function when that statement is a coroutine builder call,
 * looking through `return`: `runBlocking { }` in `fun f() { runBlocking { } }`, and `return runBlocking { }` in
 * `fun f() { return runBlocking { } }`. Null for an expression body, for no body, and for a block body that holds
 * anything else.
 */
internal fun KtNamedFunction.wrappedBuilderStatement(): KtExpression? {
    if (hasExpressionBody()) return null
    val statement = (bodyExpression as? KtBlockExpression)?.statements?.singleOrNull() ?: return null
    val value = if (statement is KtReturnExpression) statement.returnedExpression else statement
    return statement.takeIf { value?.builderCall() != null }
}

/**
 * The first line of this element as the source spells it, without backticks and cut to [MESSAGE_SPAN_LENGTH]
 * characters, so that it can sit inside the code span that opens a finding's message.
 */
internal fun PsiElement.firstLine(): String {
    val line = text.lineSequence().first().replace("`", "").trim()
    return if (line.length > MESSAGE_SPAN_LENGTH) line.take(MESSAGE_SPAN_LENGTH - ELLIPSIS.length) + ELLIPSIS else line
}

private val COROUTINE_BUILDERS = setOf("runBlocking", "runTest")
private const val MESSAGE_SPAN_LENGTH = 60
private const val ELLIPSIS = "..."
