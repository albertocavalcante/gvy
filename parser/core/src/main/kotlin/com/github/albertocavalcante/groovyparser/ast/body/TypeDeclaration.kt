// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Base class for type declarations (classes, interfaces, enums, traits).
 */
abstract class TypeDeclaration(val name: String) : Node() {

    /** Members of this type (fields, methods, etc.) */
    val members: MutableList<Node> = mutableListOf()

    override fun getChildNodes(): List<Node> = members.toList()

    override fun toString(): String = name
}
