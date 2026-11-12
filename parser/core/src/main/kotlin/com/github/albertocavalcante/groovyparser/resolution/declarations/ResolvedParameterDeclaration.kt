// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.resolution.declarations

/**
 * Represents a resolved parameter declaration.
 */
interface ResolvedParameterDeclaration : ResolvedValueDeclaration {

    /**
     * Returns true if this parameter is variadic (varargs).
     */
    fun isVariadic(): Boolean = false
}
