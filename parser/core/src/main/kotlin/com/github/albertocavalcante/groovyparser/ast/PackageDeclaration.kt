// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

/**
 * Represents a package declaration: `package com.example`
 */
class PackageDeclaration(val name: String) : Node() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "package $name"
}
