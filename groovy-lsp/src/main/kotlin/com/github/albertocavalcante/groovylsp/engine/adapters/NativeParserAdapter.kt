package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovylsp.compilation.toLspDiagnostic
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Adapter that wraps groovy-parser's [ParseResult] into the unified [ParseUnit] interface.
 *
 * This adapter translates the native Groovy AST (ModuleNode) into the parser-agnostic
 * types that LSP features consume. The native AST remains accessible via [originalNode]
 * for features that need engine-specific access.
 */
class NativeParserAdapter(private val result: ParseResult, override val uri: String) : ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    override val diagnostics: List<Diagnostic> = result.diagnostics.map { it.toLspDiagnostic() }

    override fun nodeAt(position: Position): UnifiedNode? {
        val ast = result.ast ?: return null
        // Native AST uses 1-based lines, LSP uses 0-based
        val node = ast.findNodeAt(position.line + 1, position.character + 1)
        return node?.toUnifiedNode()
    }

    override fun allSymbols(): List<UnifiedSymbol> {
        val ast = result.ast ?: return emptyList()
        val symbols = mutableListOf<UnifiedSymbol>()

        for (classNode in ast.classes) {
            symbols.add(classNode.toUnifiedSymbol())
        }

        return symbols
    }
}

/**
 * Extension to convert native Groovy AST nodes to [UnifiedNode].
 */
internal fun ASTNode.toUnifiedNode(): UnifiedNode = UnifiedNode(
    name = extractName(),
    kind = extractKind(),
    type = extractType(),
    documentation = null, // Native AST doesn't preserve comments
    range = toRange(),
    originalNode = this,
)

private fun ASTNode.extractName(): String? = when (this) {
    is ClassNode -> nameWithoutPackage
    is ConstructorNode -> declaringClass.nameWithoutPackage
    is MethodNode -> name
    is FieldNode -> name
    is PropertyNode -> name
    is Parameter -> name
    is VariableExpression -> name
    else -> null
}

private fun ASTNode.extractKind(): UnifiedNodeKind = when (this) {
    is ClassNode -> when {
        isInterface -> UnifiedNodeKind.INTERFACE
        isEnum -> UnifiedNodeKind.ENUM
        // Note: trait detection requires checking annotations
        else -> UnifiedNodeKind.CLASS
    }

    is ConstructorNode -> UnifiedNodeKind.CONSTRUCTOR
    is MethodNode -> UnifiedNodeKind.METHOD
    is FieldNode -> UnifiedNodeKind.FIELD
    is PropertyNode -> UnifiedNodeKind.PROPERTY
    is Parameter -> UnifiedNodeKind.PARAMETER
    is VariableExpression -> UnifiedNodeKind.VARIABLE
    is ClosureExpression -> UnifiedNodeKind.CLOSURE
    else -> UnifiedNodeKind.OTHER
}

private fun ASTNode.extractType(): String? = when (this) {
    is ClassNode -> name
    is MethodNode -> returnType?.nameWithoutPackage
    is FieldNode -> type?.nameWithoutPackage
    is PropertyNode -> type?.nameWithoutPackage
    is Parameter -> type?.nameWithoutPackage
    is VariableExpression -> type?.nameWithoutPackage
    else -> null
}

private fun ASTNode.toRange(): Range? {
    if (lineNumber < 1 || columnNumber < 1) return null
    val startLine = lineNumber - 1 // Convert to 0-based
    val startCol = columnNumber - 1
    val endLine = if (lastLineNumber >= 1) lastLineNumber - 1 else startLine
    // LSP end is EXCLUSIVE, Groovy lastColumnNumber is 1-based INCLUSIVE
    // 1-based inclusive column N equals 0-based exclusive column N (no subtraction needed)
    val endCol = if (lastColumnNumber >= 1) lastColumnNumber else startCol + 1
    return Range(Position(startLine, startCol), Position(endLine, endCol))
}

/**
 * Check if a method is a synthetic property accessor (getter/setter).
 * Groovy automatically generates getter/setter methods for properties, but doesn't always
 * mark them as synthetic. This function detects them by checking if:
 * - The method name matches the pattern get[PropertyName]() or set[PropertyName](value)
 * - A property with that name exists in the class
 */
private fun isSyntheticPropertyAccessor(method: MethodNode, propertyNames: Set<String>): Boolean {
    val methodName = method.name

    // Check for getter pattern: getName() with no parameters
    if (methodName.startsWith("get") && methodName.length > 3 && method.parameters.isEmpty()) {
        val propertyName = methodName.substring(3).replaceFirstChar { it.lowercase() }
        if (propertyName in propertyNames) {
            return true
        }
    }

    // Check for setter pattern: setName(value) with exactly one parameter
    if (methodName.startsWith("set") && methodName.length > 3 && method.parameters.size == 1) {
        val propertyName = methodName.substring(3).replaceFirstChar { it.lowercase() }
        if (propertyName in propertyNames) {
            return true
        }
    }

    // Check for boolean getter pattern: isName() with no parameters
    if (methodName.startsWith("is") && methodName.length > 2 && method.parameters.isEmpty()) {
        val propertyName = methodName.substring(2).replaceFirstChar { it.lowercase() }
        if (propertyName in propertyNames) {
            return true
        }
    }

    return false
}

/**
 * Extension to convert ClassNode to [UnifiedSymbol] with children.
 */
internal fun ClassNode.toUnifiedSymbol(): UnifiedSymbol {
    val children = mutableListOf<UnifiedSymbol>()

    // Collect property names to filter out synthetic getters/setters
    val propertyNames = properties.map { it.name }.toSet()

    // Add fields
    for (field in fields) {
        if (!field.isSynthetic) {
            field.toRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = field.name,
                        kind = UnifiedNodeKind.FIELD,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }
    }

    // Add methods (ConstructorNode is NOT included in methods, only in declaredConstructors)
    for (method in methods) {
        if (!method.isSynthetic && !isSyntheticPropertyAccessor(method, propertyNames)) {
            method.toRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = method.name,
                        kind = UnifiedNodeKind.METHOD,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }
    }

    // Add constructors (separate from methods in ClassNode)
    for (constructor in declaredConstructors) {
        if (!constructor.isSynthetic) {
            constructor.toRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = nameWithoutPackage, // Use class name, not <init>
                        kind = UnifiedNodeKind.CONSTRUCTOR,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }
    }

    val classRange = this.toRange() ?: Range(Position(0, 0), Position(0, 0))
    return UnifiedSymbol(
        name = nameWithoutPackage,
        kind = when {
            isInterface -> UnifiedNodeKind.INTERFACE
            isEnum -> UnifiedNodeKind.ENUM
            else -> UnifiedNodeKind.CLASS
        },
        range = classRange,
        selectionRange = classRange,
        children = children,
    )
}
