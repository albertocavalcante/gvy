package com.github.albertocavalcante.groovyparser.ast.symbols

import com.github.albertocavalcante.groovyparser.ast.SafePosition
import com.github.albertocavalcante.groovyparser.ast.safePosition
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import java.lang.reflect.Modifier
import java.net.URI
import org.codehaus.groovy.ast.Variable as GroovyVariable

/**
 * Type alias for symbol names
 */
typealias SymbolName = String

/**
 * Sealed hierarchy for all symbol types in the Groovy LSP
 */
sealed class Symbol {
    abstract val name: SymbolName
    abstract val uri: URI
    abstract val position: GroovyParserResult<SafePosition>
    abstract val node: ASTNode

    /**
     * Variable symbols (local variables, parameters)
     */
    data class Variable(
        override val name: SymbolName,
        override val uri: URI,
        override val node: ASTNode,
        val type: ClassNode?,
        val isParameter: Boolean = false,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        companion object {
            fun from(variable: GroovyVariable, uri: URI): Variable = Variable(
                name = variable.name,
                uri = uri,
                node = variable as ASTNode,
                type = variable.type,
                isParameter = variable is Parameter,
            )
        }
    }

    /**
     * Method symbols
     */
    data class Method(
        override val name: SymbolName,
        override val uri: URI,
        override val node: MethodNode,
        val parameters: List<Parameter>,
        val returnType: ClassNode?,
        val owner: ClassNode?,
        val isStatic: Boolean,
        val isAbstract: Boolean,
        val visibility: Visibility,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        val signature: String
            get() = buildString {
                val isConstructor = node.isConstructor
                val constructorName = if (isConstructor) {
                    owner?.nameWithoutPackage
                        ?: owner?.name
                        ?: node.declaringClass?.nameWithoutPackage
                        ?: node.declaringClass?.name
                        ?: "constructor"
                } else {
                    name
                }
                if (isStatic) append("static ")
                if (isAbstract) append("abstract ")
                append(visibility.keyword).append(" ")
                if (!isConstructor) {
                    append(returnType?.nameWithoutPackage ?: "def").append(" ")
                }
                append(constructorName).append("(")
                append(parameters.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" })
                append(")")
            }

        companion object {
            fun from(method: MethodNode, uri: URI): Method = Method(
                name = method.name,
                uri = uri,
                node = method,
                parameters = method.parameters.toList(),
                returnType = method.returnType,
                owner = method.declaringClass,
                isStatic = method.isStatic,
                isAbstract = method.isAbstract,
                visibility = Visibility.from(method.modifiers),
            )
        }
    }

    /**
     * Field symbols
     */
    data class Field(
        override val name: SymbolName,
        override val uri: URI,
        override val node: FieldNode,
        val type: ClassNode?,
        val owner: ClassNode?,
        val isStatic: Boolean,
        val isFinal: Boolean,
        val visibility: Visibility,
        val initialValue: String?,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        companion object {
            fun from(field: FieldNode, uri: URI): Field = Field(
                name = field.name,
                uri = uri,
                node = field,
                type = field.type,
                owner = field.owner,
                isStatic = field.isStatic,
                isFinal = field.isFinal,
                visibility = Visibility.from(field.modifiers),
                initialValue = field.initialValueExpression?.text,
            )
        }
    }

    /**
     * Property symbols (Groovy-specific)
     */
    data class Property(
        override val name: SymbolName,
        override val uri: URI,
        override val node: PropertyNode,
        val type: ClassNode?,
        val owner: ClassNode?,
        val isStatic: Boolean,
        val visibility: Visibility,
        val getter: MethodNode?,
        val setter: MethodNode?,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        companion object {
            fun from(property: PropertyNode, uri: URI): Property = Property(
                name = property.name,
                uri = uri,
                node = property,
                type = property.type,
                owner = property.declaringClass,
                isStatic = property.isStatic,
                visibility = Visibility.from(property.modifiers),
                getter = null, // TODO: PropertyNode doesn't expose getter MethodNode directly
                setter = null, // TODO: PropertyNode doesn't expose setter MethodNode directly
            )
        }
    }

    /**
     * Class symbols
     */
    data class Class(
        override val name: SymbolName,
        override val uri: URI,
        override val node: ClassNode,
        val packageName: String?,
        val superClass: ClassNode?,
        val interfaces: List<ClassNode>,
        val isInterface: Boolean,
        val isAbstract: Boolean,
        val isEnum: Boolean,
        val visibility: Visibility,
        val methods: List<MethodNode>,
        val fields: List<FieldNode>,
        val properties: List<PropertyNode>,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        val fullyQualifiedName: String
            get() = if (packageName?.isNotEmpty() == true) "$packageName.$name" else name

        /**
         * Fully qualified names of implemented interfaces.
         * Uses ClassNode.name which should contain FQN after import resolution.
         */
        val interfaceNames: List<String>
            get() = interfaces.map { it.name }

        companion object {
            fun from(classNode: ClassNode, uri: URI): Class = Class(
                name = classNode.nameWithoutPackage,
                uri = uri,
                node = classNode,
                packageName = classNode.packageName,
                superClass = classNode.superClass,
                interfaces = classNode.interfaces.toList(),
                isInterface = classNode.isInterface,
                isAbstract = classNode.isAbstract,
                isEnum = classNode.isEnum,
                visibility = Visibility.from(classNode.modifiers),
                methods = classNode.methods.toList(),
                fields = classNode.fields.toList(),
                properties = classNode.properties.toList(),
            )
        }
    }

    /**
     * Import symbols
     */
    data class Import(
        override val name: SymbolName,
        override val uri: URI,
        override val node: ImportNode,
        val importedName: String?,
        val alias: String?,
        val isStatic: Boolean,
        val isStarImport: Boolean,
        val packageName: String?,
    ) : Symbol() {
        override val position: GroovyParserResult<SafePosition> = node.safePosition()

        companion object {
            fun from(importNode: ImportNode, uri: URI): Import = Import(
                name = importNode.alias ?: importNode.className ?: importNode.packageName,
                uri = uri,
                node = importNode,
                importedName = importNode.className,
                alias = importNode.alias,
                isStatic = false, // Regular imports are not static
                isStarImport = importNode.isStar,
                packageName = importNode.packageName,
            )

            fun fromStatic(importNode: ImportNode, uri: URI): Import = Import(
                name = importNode.fieldName ?: importNode.className,
                uri = uri,
                node = importNode,
                importedName = importNode.className,
                alias = importNode.alias,
                isStatic = true,
                isStarImport = importNode.isStar,
                packageName = importNode.packageName,
            )
        }
    }
}

/**
 * Visibility levels for symbols
 */
enum class Visibility(val keyword: String) {
    PUBLIC("public"),
    PROTECTED("protected"),
    PRIVATE("private"),
    PACKAGE(""), // Package-private (default)
    INTERNAL("internal"), // Kotlin-specific, not used in Groovy
    ;

    companion object {
        fun from(modifiers: Int): Visibility = when {
            Modifier.isPrivate(modifiers) -> PRIVATE
            Modifier.isProtected(modifiers) -> PROTECTED
            Modifier.isPublic(modifiers) -> PUBLIC
            else -> PACKAGE
        }
    }
}

/**
 * Symbol categories for filtering and grouping
 */
enum class SymbolCategory {
    VARIABLE,
    METHOD,
    FIELD,
    PROPERTY,
    CLASS,
    IMPORT,
    ;

    companion object {
        fun of(symbol: Symbol): SymbolCategory = when (symbol) {
            is Symbol.Variable -> VARIABLE
            is Symbol.Method -> METHOD
            is Symbol.Field -> FIELD
            is Symbol.Property -> PROPERTY
            is Symbol.Class -> CLASS
            is Symbol.Import -> IMPORT
        }
    }
}

/**
 * Extension functions for symbols
 */

/**
 * Checks if a symbol matches a given name pattern
 */
fun Symbol.matches(pattern: String): Boolean = name.contains(pattern, ignoreCase = true)

/**
 * Checks if a symbol matches a regex pattern
 */
fun Symbol.matches(regex: Regex): Boolean = regex.matches(name)

/**
 * Gets the category of a symbol
 */
fun Symbol.category(): SymbolCategory = SymbolCategory.of(this)

/**
 * Checks if a symbol is accessible from the given context
 */
fun Symbol.isAccessibleFrom(contextUri: URI, contextClass: ClassNode?): Boolean = when (this) {
    is Symbol.Variable -> uri == contextUri // Variables are file-scoped
    is Symbol.Method -> isAccessibleMember(contextUri, contextClass)
    is Symbol.Field -> isAccessibleMember(contextUri, contextClass)
    is Symbol.Property -> isAccessibleMember(contextUri, contextClass)
    is Symbol.Class -> visibility == Visibility.PUBLIC || uri == contextUri
    is Symbol.Import -> uri == contextUri // Imports are file-scoped
}

/**
 * Helper function for checking member accessibility
 */
private fun Symbol.isAccessibleMember(contextUri: URI, contextClass: ClassNode?): Boolean {
    val memberVisibility = when (this) {
        is Symbol.Method -> visibility
        is Symbol.Field -> visibility
        is Symbol.Property -> visibility
        is Symbol.Class -> visibility
        is Symbol.Variable, is Symbol.Import -> Visibility.PUBLIC
    }

    val memberOwner = when (this) {
        is Symbol.Method -> owner
        is Symbol.Field -> owner
        is Symbol.Property -> owner
        is Symbol.Class -> node
        is Symbol.Variable, is Symbol.Import -> null
    }

    return when (memberVisibility) {
        Visibility.PUBLIC -> true
        Visibility.PACKAGE -> uri == contextUri
        Visibility.PROTECTED -> contextClass?.isDerivedFrom(memberOwner) == true
        Visibility.PRIVATE -> contextClass == memberOwner
        Visibility.INTERNAL -> uri == contextUri // Treat as package-private in Groovy
    }
}
