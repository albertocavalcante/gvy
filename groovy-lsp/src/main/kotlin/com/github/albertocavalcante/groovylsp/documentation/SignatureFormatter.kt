package com.github.albertocavalcante.groovylsp.documentation

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import java.lang.reflect.Modifier

/**
 * Formats Groovy AST nodes into rich, readable signatures for hover display.
 *
 * Features:
 * - Access modifiers (public, private, protected, package-private)
 * - Static/final/abstract markers
 * - Generic type parameters with bounds
 * - Annotations on types and parameters
 * - Default parameter values
 * - Thrown exceptions
 * - Configurable line breaking for long signatures
 */
object SignatureFormatter {
    data class Options(
        val showModifiers: Boolean = true,
        val showAnnotations: Boolean = true,
        val showDefaultValues: Boolean = true,
        val showThrows: Boolean = true,
        val maxLineLength: Int = 80,
        // Break params to multiple lines if long
        val multilineParams: Boolean = true,
    )

    /**
     * Format a method signature with full details.
     *
     * @param method The method node to format
     * @param options Formatting options
     * @return Formatted method signature
     */
    fun formatMethod(method: MethodNode, options: Options = Options()): String = buildString {
        // Annotations
        appendAnnotations(method.annotations, options, "\n")

        // Modifiers
        if (options.showModifiers) {
            val mods = formatModifiers(method.modifiers)
            if (mods.isNotBlank()) {
                append(mods)
                append(" ")
            }
        }

        // Generic type parameters
        if (method.genericsTypes != null && method.genericsTypes.isNotEmpty()) {
            append(formatTypeParameters(method.genericsTypes.toList()))
            append(" ")
        }

        // Return type
        append(formatType(method.returnType))
        append(" ")

        // Method name
        append(method.name)

        // Parameters
        val formattedParams = formatParameters(method.parameters, options)
        val paramsOneline = formattedParams.joinToString(", ")
        val signatureLength = length + paramsOneline.length + 2 // +2 for parentheses

        if (options.multilineParams && signatureLength > options.maxLineLength && method.parameters.size > 1) {
            append("(\n")
            formattedParams.forEachIndexed { index, param ->
                append("    ")
                append(param)
                if (index < formattedParams.size - 1) {
                    append(",")
                }
                append("\n")
            }
            append(")")
        } else {
            append("(")
            append(paramsOneline)
            append(")")
        }

        // Throws clause
        if (options.showThrows && method.exceptions != null && method.exceptions.isNotEmpty()) {
            append(" throws ")
            append(method.exceptions.joinToString(", ") { it.simpleNameWithoutPackage() })
        }
    }

    /**
     * Format a class signature with full details.
     *
     * @param classNode The class node to format
     * @param options Formatting options
     * @return Formatted class signature
     */
    fun formatClass(classNode: ClassNode, options: Options = Options()): String = buildString {
        // Annotations
        appendAnnotations(classNode.annotations, options, "\n")

        // Modifiers
        if (options.showModifiers) {
            val mods = formatModifiers(classNode.modifiers)
            if (mods.isNotBlank()) {
                append(mods)
                append(" ")
            }
        }

        // Class type
        when {
            classNode.isInterface -> append("interface ")
            classNode.isEnum -> append("enum ")
            classNode.isAnnotationDefinition -> append("@interface ")
            classNode.isAbstract && !options.showModifiers -> append("abstract class ")
            else -> append("class ")
        }

        // Class name
        append(classNode.simpleNameWithoutPackage())

        // Generic type parameters
        if (classNode.genericsTypes != null && classNode.genericsTypes.isNotEmpty()) {
            append(formatTypeParameters(classNode.genericsTypes.toList()))
        }

        // Extends clause
        classNode.superClass?.let { superClass ->
            if (superClass.name != "java.lang.Object" && superClass.name != "groovy.lang.Script") {
                append(" extends ")
                append(formatType(superClass))
            }
        }

        // Implements clause
        if (classNode.interfaces.isNotEmpty()) {
            append(" implements ")
            append(classNode.interfaces.joinToString(", ") { formatType(it) })
        }
    }

    /**
     * Format a field signature with full details.
     *
     * @param field The field node to format
     * @param options Formatting options
     * @return Formatted field signature
     */
    fun formatField(field: FieldNode, options: Options = Options()): String = buildString {
        // Annotations
        appendAnnotations(field.annotations, options, " ")

        // Modifiers
        if (options.showModifiers) {
            val mods = formatModifiers(field.modifiers)
            if (mods.isNotBlank()) {
                append(mods)
                append(" ")
            }
        }

        // Type
        append(formatType(field.type))
        append(" ")

        // Field name
        append(field.name)

        // Initial value
        if (options.showDefaultValues && field.initialExpression != null) {
            append(" = ")
            append(field.initialExpression.text)
        }
    }

    /**
     * Format a parameter with full details.
     *
     * @param param The parameter to format
     * @param options Formatting options
     * @return Formatted parameter
     */
    fun formatParameter(param: Parameter, options: Options = Options()): String = buildString {
        // Annotations
        appendAnnotations(param.annotations, options, " ")

        // Type
        append(formatType(param.type))
        append(" ")

        // Parameter name
        append(param.name)

        // Default value
        if (options.showDefaultValues && param.hasInitialExpression()) {
            append(" = ")
            append(param.initialExpression.text)
        }
    }

    /**
     * Format method parameters as a list of strings.
     */
    private fun formatParameters(params: Array<Parameter>, options: Options): List<String> =
        params.map { formatParameter(it, options) }

    /**
     * Format generic type parameters.
     * Examples:
     * - <T>
     * - <T extends Comparable<T>>
     * - <K, V>
     * - <T extends Serializable & Comparable<T>>
     */
    private fun formatTypeParameters(typeParams: List<GenericsType>): String {
        if (typeParams.isEmpty()) return ""

        return buildString {
            append("<")
            append(typeParams.joinToString(", ") { formatGenericType(it) })
            append(">")
        }
    }

    /**
     * Format a single generic type with bounds.
     */
    private fun formatGenericType(genericsType: GenericsType): String = buildString {
        append(genericsType.name)

        // Upper bounds (extends)
        if (genericsType.upperBounds != null && genericsType.upperBounds.isNotEmpty()) {
            val bounds = genericsType.upperBounds.filter { it.name != "java.lang.Object" }
            if (bounds.isNotEmpty()) {
                append(" extends ")
                append(bounds.joinToString(" & ") { formatType(it) })
            }
        }

        // Lower bounds (super)
        if (genericsType.lowerBound != null) {
            append(" super ")
            append(formatType(genericsType.lowerBound))
        }

        // Wildcard
        if (genericsType.isWildcard && genericsType.upperBounds == null && genericsType.lowerBound == null) {
            return "?"
        }
    }

    /**
     * Format a type with generics.
     * Examples:
     * - String
     * - List<String>
     * - Map<String, List<Integer>>
     */
    private fun formatType(type: ClassNode): String = buildString {
        append(type.simpleNameWithoutPackage())

        if (type.genericsTypes != null && type.genericsTypes.isNotEmpty()) {
            append("<")
            append(type.genericsTypes.joinToString(", ") { formatGenericType(it) })
            append(">")
        }
    }

    /**
     * Format modifiers (public, private, static, final, etc.).
     */
    private fun formatModifiers(modifiers: Int): String {
        val parts = mutableListOf<String>()
        if (Modifier.isPublic(modifiers)) parts += "public"
        if (Modifier.isPrivate(modifiers)) parts += "private"
        if (Modifier.isProtected(modifiers)) parts += "protected"
        if (Modifier.isStatic(modifiers)) parts += "static"
        if (Modifier.isFinal(modifiers)) parts += "final"
        if (Modifier.isAbstract(modifiers)) parts += "abstract"
        if (Modifier.isSynchronized(modifiers)) parts += "synchronized"
        if (Modifier.isVolatile(modifiers)) parts += "volatile"
        if (Modifier.isTransient(modifiers)) parts += "transient"
        return parts.joinToString(" ")
    }

    /**
     * Helper function to append formatted annotations to a StringBuilder.
     */
    private fun StringBuilder.appendAnnotations(
        annotations: List<AnnotationNode>,
        options: Options,
        trailing: String,
    ) {
        if (options.showAnnotations && annotations.isNotEmpty()) {
            annotations.forEach { annotation ->
                append("@${annotation.classNode.simpleNameWithoutPackage()}")
                if (annotation.members.isNotEmpty()) {
                    append("(")
                    append(
                        annotation.members.entries.joinToString(", ") { (key, value) ->
                            "$key = ${value.text}"
                        },
                    )
                    append(")")
                }
                append(trailing)
            }
        }
    }
}

/**
 * Extension function to get class name without package, correctly handling nested classes.
 * This overrides the default ClassNode.nameWithoutPackage which doesn't handle nested classes with $.
 */
private fun ClassNode.simpleNameWithoutPackage(): String = name.substringAfterLast('.').substringAfterLast('$')
