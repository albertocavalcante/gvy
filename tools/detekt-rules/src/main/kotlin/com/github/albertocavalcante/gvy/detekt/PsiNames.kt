// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType

// Dotted names that gvy's rules compare, read from PSI name segments rather than source text. Whitespace, line breaks,
// comments and backticks between or around the segments therefore do not change the name: `java.util.regex.Pattern`,
// `` java.util.`regex`.Pattern ``, `java.util./* c */regex.Pattern` and a chain split across lines read the same.

/**
 * The dotted name this expression spells, such as `java.util.regex.Pattern`, or null when it is not a chain of plain
 * names: a call, a safe call or `this` anywhere in it makes it null.
 */
internal fun KtExpression.qualifiedName(): String? = when (this) {
    is KtNameReferenceExpression -> getReferencedName()
    is KtDotQualifiedExpression -> receiverExpression.qualifiedName()?.let { receiver ->
        selectorExpression?.qualifiedName()?.let { selector -> "$receiver.$selector" }
    }
    else -> null
}

/** The dotted name this type spells, without type arguments: `kotlin.jvm.Volatile`, or `List` for `List<String>`. */
internal fun KtUserType.qualifiedName(): String? {
    val name = referencedName ?: return null
    val outer = qualifier ?: return name
    return outer.qualifiedName()?.let { "$it.$name" }
}
