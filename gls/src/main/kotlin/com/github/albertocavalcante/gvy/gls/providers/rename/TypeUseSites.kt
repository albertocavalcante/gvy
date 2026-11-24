// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/** The syntactic position of a use of a class, as a refusal names it. */
internal enum class TypeUseKind(val label: String) {
    SUPERCLASS("superclass"),
    INTERFACE("implemented interface"),
    PERMITTED_SUBCLASS("permitted subclass"),
    TYPE_PARAMETER_BOUND("type parameter bound"),
    TYPE_ARGUMENT("type argument"),
    RETURN_TYPE("return type"),
    EXCEPTION("thrown exception type"),
    PARAMETER("parameter type"),
    FIELD("field type"),
    VARIABLE("variable type"),
    CAST("cast type"),
    ARRAY_ELEMENT("array creation type"),
    CLASS_LITERAL("class reference"),
    CONSTRUCTOR_CALL("constructed type"),
    ANONYMOUS_SUBCLASS("supertype of an anonymous class"),
    ANNOTATION("annotation"),
    IMPORT("import"),
    OUTER_QUALIFIER("qualifier of a nested type"),
}

/**
 * A node the AST attributes to a use of [type] (a class as declared, never a use-site node) at [kind]; the use spells
 * the name of [type] somewhere in the source range of [node].
 */
internal class TypeUseSite(val type: ClassNode, val kind: TypeUseKind, val node: ASTNode)

/**
 * A name the AST attributes to something that is not a class: a variable, a called method, a declared method. It is
 * spelled in [node], after the end of every node of [after] and before the start of every node of [until].
 */
internal class OtherNameSite(
    val name: String,
    val node: ASTNode,
    val after: List<ASTNode> = emptyList(),
    val until: List<ASTNode> = emptyList(),
)

/** The positioned uses of classes, and other declared or called names, of one module, found by walking its AST. */
internal class TypeUseSites private constructor(val uses: List<TypeUseSite>, val otherNames: List<OtherNameSite>) {
    companion object {
        fun of(module: ModuleNode): TypeUseSites {
            val collector = TypeUseCollector()
            module.classes.forEach(collector::visitClass)
            module.statementBlock?.visit(collector)
            return TypeUseSites(collector.uses, collector.otherNames)
        }
    }
}
