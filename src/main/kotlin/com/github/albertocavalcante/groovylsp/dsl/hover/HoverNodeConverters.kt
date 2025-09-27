package com.github.albertocavalcante.groovylsp.dsl.hover

import com.github.albertocavalcante.groovylsp.errors.LspResult
import com.github.albertocavalcante.groovylsp.errors.toLspResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover

/**
 * Extension functions for formatting specific AST node types
 */

/**
 * Format a VariableExpression for hover
 */
fun VariableExpression.toHoverContent(): HoverContent.Code = HoverContent.Code("${type.nameWithoutPackage} $name")

/**
 * Format a MethodNode for hover
 */
fun MethodNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Method",
    content = listOf(
        HoverContent.Code(signature()),
        HoverContent.KeyValue(
            listOf(
                "Return Type" to (returnType?.nameWithoutPackage ?: "def"),
                "Modifiers" to modifiersString(),
                "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
            ),
        ),
    ),
)

/**
 * Format a ClassNode for hover
 */
fun ClassNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Class",
    content = buildList {
        add(HoverContent.Code(classSignature()))

        if (methods.isNotEmpty()) {
            add(
                HoverContent.Section(
                    "Methods",
                    listOf(HoverContent.List(methods.map { "${it.name}(${it.parametersString()})" })),
                ),
            )
        }

        if (fields.isNotEmpty()) {
            add(
                HoverContent.Section(
                    "Fields",
                    listOf(HoverContent.List(fields.map { "${it.type.nameWithoutPackage} ${it.name}" })),
                ),
            )
        }
    },
)

/**
 * Format a FieldNode for hover
 */
fun FieldNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Field",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage} $name"),
        HoverContent.KeyValue(
            listOf(
                "Type" to type.nameWithoutPackage,
                "Modifiers" to modifiersString(),
                "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                "Initial Value" to (initialValueExpression?.text ?: "none"),
            ),
        ),
    ),
)

/**
 * Format a PropertyNode for hover
 */
fun PropertyNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Property",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage} $name"),
        HoverContent.KeyValue(
            listOf(
                "Type" to type.nameWithoutPackage,
                "Modifiers" to modifiersString(),
                "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                "Getter" to if (getterBlock != null) "available" else "none",
                "Setter" to if (setterBlock != null) "available" else "none",
            ),
        ),
    ),
)

/**
 * Format a Parameter for hover
 */
fun Parameter.toHoverContent(): HoverContent.Code = HoverContent.Code("${type.nameWithoutPackage} $name")

/**
 * Format a MethodCallExpression for hover
 */
fun MethodCallExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Method Call",
    content = listOf(
        HoverContent.Code("$method(${argumentsString()})"),
        HoverContent.KeyValue(
            listOf(
                "Method" to method.toString(),
                "Object" to (objectExpression?.toString() ?: "this"),
                "Arguments" to argumentsString(),
            ),
        ),
    ),
)

/**
 * Format a BinaryExpression for hover
 */
fun BinaryExpression.toHoverContent(): HoverContent = when (operation.text) {
    "=" -> HoverContent.Section(
        "Assignment",
        listOf(HoverContent.Code("$leftExpression = $rightExpression")),
    )
    else -> HoverContent.Section(
        "Binary Expression",
        listOf(HoverContent.Code("$leftExpression ${operation.text} $rightExpression")),
    )
}

/**
 * Format a DeclarationExpression for hover
 */
fun DeclarationExpression.toHoverContent(): HoverContent {
    val varExpr = leftExpression as? VariableExpression
    val type = varExpr?.type?.nameWithoutPackage ?: "def"
    val name = varExpr?.name ?: "unknown"

    return HoverContent.Section(
        "Variable Declaration",
        listOf(
            HoverContent.Code("$type $name = $rightExpression"),
            HoverContent.KeyValue(
                listOf(
                    "Type" to type,
                    "Name" to name,
                    "Initial Value" to rightExpression.toString(),
                ),
            ),
        ),
    )
}

/**
 * Format a ClosureExpression for hover
 */
fun ClosureExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Closure",
    content = listOf(
        HoverContent.Code("{ ${parametersString()} -> ... }"),
        HoverContent.KeyValue(
            listOf(
                "Parameters" to parametersString(),
                "Variables in Scope" to variableScope.declaredVariables.keys.joinToString(", "),
            ),
        ),
    ),
)

/**
 * Format a ConstantExpression for hover
 */
fun ConstantExpression.toHoverContent(): HoverContent {
    val typeDescription = when (type.name) {
        "java.lang.String" -> "String literal"
        "java.lang.Integer", "int" -> "Integer literal"
        "java.lang.Double", "double" -> "Double literal"
        "java.lang.Boolean", "boolean" -> "Boolean literal"
        else -> "Constant"
    }

    return HoverContent.Section(
        typeDescription,
        listOf(
            HoverContent.Code(text),
            HoverContent.KeyValue(
                listOf(
                    "Type" to type.nameWithoutPackage,
                    "Value" to text,
                ),
            ),
        ),
    )
}

/**
 * Format a GStringExpression for hover
 */
fun GStringExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "GString",
    content = listOf(
        HoverContent.Code(text),
        HoverContent.KeyValue(
            listOf(
                "Type" to "GString",
                "Template" to text,
                "Values" to values.size.toString(),
            ),
        ),
    ),
)

/**
 * Format an ImportNode for hover
 */
fun ImportNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Import",
    content = listOf(
        HoverContent.Code(formatImport()),
        HoverContent.KeyValue(
            listOf(
                "Class" to className,
                "Alias" to (alias ?: "none"),
                "Package" to (packageName ?: "default"),
                "Star Import" to isStar.toString(),
            ),
        ),
    ),
)

/**
 * Format a PackageNode for hover
 */
fun PackageNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Package",
    content = listOf(
        HoverContent.Code("package $name"),
    ),
)

/**
 * Format an AnnotationNode for hover
 */
fun AnnotationNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Annotation",
    content = listOf(
        HoverContent.Code("@${classNode.nameWithoutPackage}"),
        HoverContent.KeyValue(
            listOf(
                "Type" to classNode.nameWithoutPackage,
                "Members" to (members?.size?.toString() ?: "0"),
            ),
        ),
    ),
)

/**
 * Generic formatter that dispatches to specific formatters
 */
fun ASTNode.toHoverContent(): HoverContent = when {
    // Declarations and definitions
    isDeclarationNode() -> formatDeclarationNode()
    // Expressions
    isExpressionNode() -> formatExpressionNode()
    // Annotations and imports
    isMetadataNode() -> formatMetadataNode()
    // Default fallback
    else -> HoverContent.Section(
        "AST Node",
        listOf(
            HoverContent.Text("${this::class.java.simpleName}"),
            HoverContent.Code(toString()),
        ),
    )
}

/**
 * Helper functions for node categorization and formatting
 */
private fun ASTNode.isDeclarationNode(): Boolean = this is MethodNode || this is ClassNode ||
    this is FieldNode || this is PropertyNode || this is Parameter

private fun ASTNode.isExpressionNode(): Boolean = this is VariableExpression || this is MethodCallExpression ||
    this is BinaryExpression || this is DeclarationExpression || this is ClosureExpression ||
    this is ConstantExpression || this is GStringExpression

private fun ASTNode.isMetadataNode(): Boolean = this is ImportNode || this is PackageNode || this is AnnotationNode

private fun ASTNode.formatDeclarationNode(): HoverContent = when (this) {
    is MethodNode -> toHoverContent()
    is ClassNode -> toHoverContent()
    is FieldNode -> toHoverContent()
    is PropertyNode -> toHoverContent()
    is Parameter -> toHoverContent()
    else -> HoverContent.Text("Unknown declaration")
}

private fun ASTNode.formatExpressionNode(): HoverContent = when (this) {
    is VariableExpression -> toHoverContent()
    is MethodCallExpression -> toHoverContent()
    is BinaryExpression -> toHoverContent()
    is DeclarationExpression -> toHoverContent()
    is ClosureExpression -> toHoverContent()
    is ConstantExpression -> toHoverContent()
    is GStringExpression -> toHoverContent()
    else -> HoverContent.Text("Unknown expression")
}

private fun ASTNode.formatMetadataNode(): HoverContent = when (this) {
    is ImportNode -> toHoverContent()
    is PackageNode -> toHoverContent()
    is AnnotationNode -> toHoverContent()
    else -> HoverContent.Text("Unknown metadata")
}

/**
 * Helper functions for generating strings
 */
private fun MethodNode.signature(): String = buildString {
    if (isStatic) append("static ")
    if (isAbstract) append("abstract ")
    append(modifiersString()).append(" ")
    append(returnType?.nameWithoutPackage ?: "def").append(" ")
    append(name).append("(")
    append(parametersString())
    append(")")
}

private fun ClassNode.classSignature(): String = buildString {
    when {
        isInterface -> append("interface ")
        isEnum -> append("enum ")
        isAbstract -> append("abstract class ")
        else -> append("class ")
    }
    append(nameWithoutPackage)
    superClass?.let { if (it.name != "java.lang.Object") append(" extends ${it.nameWithoutPackage}") }
    if (interfaces.isNotEmpty()) {
        append(" implements ${interfaces.joinToString(", ") { it.nameWithoutPackage }}")
    }
}

private fun MethodNode.parametersString(): String =
    parameters.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" }

private fun ClosureExpression.parametersString(): String =
    parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""

private fun MethodCallExpression.argumentsString(): String = arguments.toString()

private fun ImportNode.formatImport(): String = buildString {
    append("import ")
    if (isStatic) append("static ")
    append(className)
    if (isStatic && fieldName != null && !isStar) {
        append(".$fieldName")
    }
    if (isStar) append(".*")
    alias?.let { append(" as $it") }
}

private fun ASTNode.modifiersString(): String = buildString {
    val modifiers = when (val node = this@modifiersString) {
        is MethodNode -> node.modifiers
        is FieldNode -> node.modifiers
        is ClassNode -> node.modifiers
        else -> 0
    }

    val parts = mutableListOf<String>()
    if (java.lang.reflect.Modifier.isPublic(modifiers)) parts += "public"
    if (java.lang.reflect.Modifier.isPrivate(modifiers)) parts += "private"
    if (java.lang.reflect.Modifier.isProtected(modifiers)) parts += "protected"
    if (java.lang.reflect.Modifier.isStatic(modifiers)) parts += "static"
    if (java.lang.reflect.Modifier.isFinal(modifiers)) parts += "final"
    if (java.lang.reflect.Modifier.isAbstract(modifiers)) parts += "abstract"

    append(parts.joinToString(" "))
}

/**
 * Main entry point for creating hover from any AST node
 */
fun createHoverFor(node: ASTNode): LspResult<Hover> = hover {
    when (val nodeContent = node.toHoverContent()) {
        is HoverContent.Text -> text(nodeContent.value)
        is HoverContent.Code -> code(nodeContent.language, nodeContent.value)
        is HoverContent.Markdown -> markdown(nodeContent.value)
        is HoverContent.Section -> section(nodeContent.title) {
            nodeContent.content.forEach { item: HoverContent ->
                when (item) {
                    is HoverContent.Text -> text(item.value)
                    is HoverContent.Code -> code(item.language, item.value)
                    is HoverContent.Markdown -> markdown(item.value)
                    is HoverContent.List -> list(item.items)
                    is HoverContent.KeyValue -> keyValue(item.pairs)
                    else -> {}
                }
            }
        }
        is HoverContent.List -> list(nodeContent.items)
        is HoverContent.KeyValue -> keyValue(nodeContent.pairs)
    }
}.toLspResult()
