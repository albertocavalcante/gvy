package com.github.albertocavalcante.gvy.semantics

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode

private val PRIMITIVE_NAMES = setOf("int", "long", "double", "float", "boolean", "byte", "char", "short", "void")

/** Convert a Groovy [ClassNode] to a [SemanticType]. */
fun ClassNode.toSemanticType(): SemanticType = when {
    equals(ClassHelper.dynamicType()) -> SemanticType.Dynamic()
    isArray -> SemanticType.Array(componentType.toSemanticType())
    name in PRIMITIVE_NAMES -> primitiveOrKnown(name)
    else -> SemanticType.Known(name)
}

private fun primitiveOrKnown(fqn: String): SemanticType = when (fqn) {
    "int" -> TypeConstants.INT
    "long" -> TypeConstants.LONG
    "double" -> TypeConstants.DOUBLE
    "float" -> TypeConstants.FLOAT
    "boolean" -> TypeConstants.BOOLEAN
    "byte" -> TypeConstants.BYTE
    "char" -> TypeConstants.CHAR
    "short" -> TypeConstants.SHORT
    "void" -> TypeConstants.VOID
    else -> SemanticType.Known(fqn)
}

/** Workspace symbol ids spell class names with slashes (`demo/money/Money`); types carry dotted names. */
fun String.toIndexClassName(): String = replace('.', '/')
