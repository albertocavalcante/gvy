package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.Expression
import java.lang.reflect.Modifier

/**
 * Kotlin-idiomatic formatter for AST nodes to human-readable strings.
 * Uses extension functions and Kotlin's buildString DSL for clean code.
 */
object NodeFormatter {
    internal const val MAX_DISPLAY_LENGTH = 50
    internal const val ELLIPSIS_LENGTH = 3
}

/**
 * Format a method node for hover display.
 * Shows visibility, static/final modifiers, name, parameters, and return type.
 */
fun MethodNode.toHoverString(): String = buildString {
    // Add modifiers
    when {
        isPublic && !isSyntheticPublic -> append("public ")
        isProtected -> append("protected ")
        isPrivate -> append("private ")
    }

    if (isStatic) append("static ")
    if (isFinal) append("final ")
    if (isAbstract) append("abstract ")

    // Add return type (def if not specified)
    if (returnType?.name != "java.lang.Object") {
        append("${returnType.nameWithoutPackage} ")
    } else {
        append("def ")
    }

    // Add method name and parameters
    append("$name(")
    append(parameters.joinToString(", ") { it.toParameterString() })
    append(")")
}

/**
 * Format a variable for hover display.
 * Shows type and name, with initialization if available.
 */
fun Variable.toHoverString(): String = buildString {
    // Show type
    val typeName = if (isDynamicTyped) {
        "def"
    } else {
        type.nameWithoutPackage
    }
    append("$typeName $name")

    // Show initial value if it's a field or property with initializer
    when (this@toHoverString) {
        is FieldNode -> initialExpression?.let { expr ->
            append(" = ${expr.toDisplayString()}")
        }
        is PropertyNode -> initialExpression?.let { expr ->
            append(" = ${expr.toDisplayString()}")
        }
    }
}

/**
 * Format a class node for hover display.
 * Shows package, modifiers, class type, name, and inheritance.
 */
fun ClassNode.toHoverString(): String = buildString {
    // Add package if present
    packageName?.let { pkg ->
        appendLine("package $pkg")
        appendLine()
    }

    // Add modifiers
    if (Modifier.isPublic(modifiers) && !isSyntheticPublic) append("public ")
    if (Modifier.isAbstract(modifiers)) append("abstract ")
    if (Modifier.isFinal(modifiers)) append("final ")

    // Add class type
    when {
        isInterface -> append("interface ")
        isEnum -> append("enum ")
        isAnnotationDefinition -> append("@interface ")
        else -> append("class ")
    }

    // Add name
    append(nameWithoutPackage)

    // Add inheritance
    superClass?.let { superType ->
        if (superType.name != "java.lang.Object" && superType.name != "groovy.lang.Script") {
            append(" extends ${superType.nameWithoutPackage}")
        }
    }

    // Add interfaces (if any)
    val interfaceNames = interfaces
        ?.filter { it.name != "groovy.lang.GroovyObject" }
        ?.map { it.nameWithoutPackage }

    if (!interfaceNames.isNullOrEmpty()) {
        append(" implements ")
        append(interfaceNames.joinToString(", "))
    }
}

/**
 * Format a field node for hover display.
 * Shows modifiers, type, and name.
 */
fun FieldNode.toHoverString(): String = buildString {
    // Add modifiers
    if (Modifier.isPublic(modifiers)) append("public ")
    if (Modifier.isProtected(modifiers)) append("protected ")
    if (Modifier.isPrivate(modifiers)) append("private ")
    if (Modifier.isStatic(modifiers)) append("static ")
    if (Modifier.isFinal(modifiers)) append("final ")

    // Add type and name
    val typeName = if (type.name == "java.lang.Object") "def" else type.nameWithoutPackage
    append("$typeName $name")

    // Add initial value if available
    initialExpression?.let { expr ->
        append(" = ${expr.toDisplayString()}")
    }
}

/**
 * Format a property node for hover display.
 * Shows type and name with getter/setter information.
 */
fun PropertyNode.toHoverString(): String = buildString {
    // Add modifiers
    if (Modifier.isPublic(modifiers)) append("public ")
    if (Modifier.isProtected(modifiers)) append("protected ")
    if (Modifier.isPrivate(modifiers)) append("private ")
    if (Modifier.isStatic(modifiers)) append("static ")
    if (Modifier.isFinal(modifiers)) append("final ")

    // Add type and name
    val typeName = if (type.name == "java.lang.Object") "def" else type.nameWithoutPackage
    append("$typeName $name")

    // Indicate if it's a property (has getters/setters)
    append(" (property)")
}

/**
 * Format a parameter for display in method signatures.
 */
private fun Parameter.toParameterString(): String = buildString {
    val typeName = if (isDynamicTyped) "def" else type.nameWithoutPackage
    append("$typeName $name")

    // Show default value if available
    initialExpression?.let { expr ->
        append(" = ${expr.toDisplayString()}")
    }
}

/**
 * Get a display-friendly representation of an expression.
 * Used for showing default values and initializers.
 */
private fun Expression.toDisplayString(): String = with(NodeFormatter) {
    when {
        text.length <= MAX_DISPLAY_LENGTH -> text
        else -> "${text.take(MAX_DISPLAY_LENGTH - ELLIPSIS_LENGTH)}..."
    }
}

/**
 * Get documentation content from Groovydoc comments.
 * Converts Groovydoc to markdown format for hover display.
 */
fun AnnotatedNode.getDocumentation(): String? {
    val groovydoc = groovydoc ?: return null

    return buildString {
        // Extract the main description
        val content = groovydoc.content?.trim()
        if (!content.isNullOrEmpty()) {
            appendLine(content)
        }

        // Add @param, @return, @throws information if available
        // Note: This is a simplified version - real Groovydoc parsing would be more complex
        val docText = groovydoc.toString()
        if (docText.contains("@param")) {
            appendLine()
            appendLine("**Parameters:**")
            // Extract param info (simplified)
        }

        if (docText.contains("@return")) {
            appendLine()
            appendLine("**Returns:**")
            // Extract return info (simplified)
        }
    }.takeIf { it.isNotBlank() }
}
