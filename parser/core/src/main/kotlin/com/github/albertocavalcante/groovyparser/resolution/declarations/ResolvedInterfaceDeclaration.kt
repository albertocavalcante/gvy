// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * Represents a resolved interface declaration.
 */
interface ResolvedInterfaceDeclaration : ResolvedTypeDeclaration {

    override fun isInterface(): Boolean = true

    override fun asInterface(): ResolvedInterfaceDeclaration = this

    /**
     * The interfaces extended by this interface.
     */
    val extendedInterfaces: List<ResolvedReferenceType>
}
