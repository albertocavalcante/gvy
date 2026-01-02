package com.github.albertocavalcante.groovylsp.dsl.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownContent
import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import com.github.albertocavalcante.groovyparser.errors.toGroovyParserResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Extension functions for formatting specific AST node types
 */

/**
 * Format a VariableExpression for hover.
 * Uses TypeInferencer when the declared type is dynamic (def).
 */
fun VariableExpression.toMarkdownContent(): MarkdownContent.Code {
    val displayType = if (type.isDynamic()) {
        // For def variables, show "def" as the type
        "def"
    } else {
        type.nameWithoutPackage
    }
    return MarkdownContent.Code("$displayType $name")
}

/**
 * Format a MethodNode for hover
 */
fun MethodNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Method",
    content = listOf(
        MarkdownContent.Code(signature()),
        MarkdownContent.KeyValue(
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
fun ClassNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Class",
    content = buildList {
        add(MarkdownContent.Code(classSignature()))

        if (methods.isNotEmpty()) {
            add(
                MarkdownContent.Section(
                    "Methods",
                    listOf(MarkdownContent.List(methods.map { "${it.name}(${it.parametersString()})" })),
                ),
            )
        }

        if (fields.isNotEmpty()) {
            add(
                MarkdownContent.Section(
                    "Fields",
                    listOf(MarkdownContent.List(fields.map { "${it.type.nameWithoutPackage} ${it.name}" })),
                ),
            )
        }
    },
)

/**
 * Format a FieldNode for hover
 */
fun FieldNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Field",
    content = listOf(
        MarkdownContent.Code("${type.nameWithoutPackage} $name"),
        MarkdownContent.KeyValue(
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
fun PropertyNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Property",
    content = listOf(
        MarkdownContent.Code("${type.nameWithoutPackage} $name"),
        MarkdownContent.KeyValue(
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
fun Parameter.toMarkdownContent(): MarkdownContent.Code = MarkdownContent.Code("${type.nameWithoutPackage} $name")

/**
 * Format a MethodCallExpression for hover
 */
fun MethodCallExpression.toMarkdownContent(): MarkdownContent {
    val methodName = displayMethodName()
    val receiver = displayReceiver()
    val arguments = displayArguments()
    val callOperator = displayCallOperator()

    val signature = buildString {
        if (!isImplicitThis) {
            append(receiver)
            append(callOperator)
        }
        append(methodName)
        append("(")
        append(arguments)
        append(")")
    }

    val argumentSummary = arguments.ifBlank { "none" }

    return MarkdownContent.Section(
        title = "Method Call",
        content = listOf(
            MarkdownContent.Code(signature),
            MarkdownContent.KeyValue(
                listOf(
                    "Method" to methodName,
                    "Receiver" to receiver,
                    "Arguments" to argumentSummary,
                ),
            ),
        ),
    )
}

/**
 * Format a BinaryExpression for hover
 */
fun BinaryExpression.toMarkdownContent(): MarkdownContent = when (operation.text) {
    "=" -> MarkdownContent.Section(
        "Assignment",
        listOf(MarkdownContent.Code("$leftExpression = $rightExpression")),
    )

    else -> MarkdownContent.Section(
        "Binary Expression",
        listOf(MarkdownContent.Code("$leftExpression ${operation.text} $rightExpression")),
    )
}

/**
 * Format a DeclarationExpression for hover.
 * Uses TypeInferencer to infer the type for def variables.
 */
fun DeclarationExpression.toMarkdownContent(): MarkdownContent {
    val varExpr = leftExpression as? VariableExpression
    val name = varExpr?.name ?: "unknown"

    // Use TypeInferencer for better type inference
    val inferredType = TypeInferencer.inferType(this)
    val displayType = inferredType.substringAfterLast('.')

    return MarkdownContent.Section(
        "Variable Declaration",
        listOf(
            MarkdownContent.Code("$displayType $name"),
            MarkdownContent.KeyValue(
                listOf(
                    "Inferred Type" to inferredType,
                    "Name" to name,
                    "Initial Value" to rightExpression.text,
                ),
            ),
        ),
    )
}

/**
 * Format a ClosureExpression for hover
 */
fun ClosureExpression.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Closure",
    content = listOf(
        MarkdownContent.Code("{ ${parametersString()} -> ... }"),
        MarkdownContent.KeyValue(
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
fun ConstantExpression.toMarkdownContent(): MarkdownContent {
    val typeDescription = when (type.name) {
        "java.lang.String" -> "String literal"
        "java.lang.Integer", "int" -> "Integer literal"
        "java.lang.Double", "double" -> "Double literal"
        "java.lang.Boolean", "boolean" -> "Boolean literal"
        else -> "Constant"
    }

    return MarkdownContent.Section(
        typeDescription,
        listOf(
            MarkdownContent.Code(text),
            MarkdownContent.KeyValue(
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
fun GStringExpression.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "GString",
    content = listOf(
        MarkdownContent.Code(text),
        MarkdownContent.KeyValue(
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
fun ImportNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Import",
    content = listOf(
        MarkdownContent.Code(formatImport()),
        MarkdownContent.KeyValue(
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
fun PackageNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Package",
    content = listOf(
        MarkdownContent.Code("package $name"),
    ),
)

/**
 * Format an AnnotationNode for hover
 */
fun AnnotationNode.toMarkdownContent(): MarkdownContent = MarkdownContent.Section(
    title = "Annotation",
    content = listOf(
        MarkdownContent.Code("@${classNode.nameWithoutPackage}"),
        MarkdownContent.KeyValue(
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
fun ASTNode.toMarkdownContent(): MarkdownContent = when {
    // Declarations and definitions
    isDeclarationNode() -> formatDeclarationNode()
    // Expressions
    isExpressionNode() -> formatExpressionNode()
    // Annotations and imports
    isMetadataNode() -> formatMetadataNode()
    // Default fallback
    else -> MarkdownContent.Section(
        "AST Node",
        listOf(
            MarkdownContent.Text("${this::class.java.simpleName}"),
            MarkdownContent.Code(toString()),
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

private fun ASTNode.formatDeclarationNode(): MarkdownContent = when (this) {
    is MethodNode -> toMarkdownContent()
    is ClassNode -> toMarkdownContent()
    is FieldNode -> toMarkdownContent()
    is PropertyNode -> toMarkdownContent()
    is Parameter -> toMarkdownContent()
    else -> MarkdownContent.Text("Unknown declaration")
}

private fun ASTNode.formatExpressionNode(): MarkdownContent = when (this) {
    is VariableExpression -> toMarkdownContent()
    is MethodCallExpression -> toMarkdownContent()
    is BinaryExpression -> toMarkdownContent()
    is DeclarationExpression -> toMarkdownContent()
    is ClosureExpression -> toMarkdownContent()
    is ConstantExpression -> toMarkdownContent()
    is GStringExpression -> toMarkdownContent()
    else -> MarkdownContent.Text("Unknown expression")
}

private fun ASTNode.formatMetadataNode(): MarkdownContent = when (this) {
    is ImportNode -> toMarkdownContent()
    is PackageNode -> toMarkdownContent()
    is AnnotationNode -> toMarkdownContent()
    else -> MarkdownContent.Text("Unknown metadata")
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

private fun MethodCallExpression.displayMethodName(): String =
    methodAsString ?: method.text.takeUnless { it.isNullOrBlank() } ?: "<dynamic>"

private fun MethodCallExpression.displayReceiver(): String = when {
    isImplicitThis -> "this"
    else -> objectExpression?.text?.takeUnless { it.isNullOrBlank() } ?: "this"
}

private fun MethodCallExpression.displayCallOperator(): String = when {
    isSafe -> "?."
    isSpreadSafe -> "*."
    else -> "."
}

private fun MethodCallExpression.displayArguments(): String {
    val expression = arguments
    val values = when (expression) {
        is ArgumentListExpression -> expression.expressions.map { it.displayArgument() }
        is TupleExpression -> expression.expressions.map { it.displayArgument() }
        is MapExpression -> expression.mapEntryExpressions.map { it.displayNamedArgument() }
        else -> listOf(expression.text.takeIf { it.isNotBlank() } ?: "")
    }

    return values.filter { it.isNotBlank() }.joinToString(", ")
}

private fun org.codehaus.groovy.ast.expr.Expression.displayArgument(): String = when (this) {
    is ConstantExpression -> text
    is GStringExpression -> text
    is VariableExpression -> name
    is ClosureExpression -> "{ ... }"
    else -> text.takeIf { it.isNotBlank() } ?: toString()
}

private fun MapEntryExpression.displayNamedArgument(): String {
    val key = keyExpression.displayArgument()
    val value = valueExpression.displayArgument()
    return "$key: $value"
}

private fun ImportNode.formatImport(): String = buildString {
    append("import ")
    if (isStatic) append("static ")
    append(className)
    if (isStatic && fieldName != null && !isStar) {
        append(".$fieldName")
    }
    if (isStar) append(".*")

    // NOTE: Groovy AST quirk / tradeoff:
    // When there's no explicit alias (e.g., `import java.util.List`), Groovy sets
    // ImportNode.alias to the simple class name ("List"). To avoid showing redundant
    // "as List" in hover text, we only display the alias if it differs from the simple name.
    // This correctly distinguishes `import java.util.List` from `import java.util.List as MyList`.
    val simpleClassName = className.substringAfterLast('.')
    alias?.let {
        if (it != simpleClassName) {
            append(" as $it")
        }
    }
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
 * Bridge function to build LSP Hover from Markdown DSL
 */
private fun buildHover(block: MarkdownBuilder.() -> Unit): Hover {
    val content = markdown(block)
    val markupContent = MarkupContent().apply {
        kind = MarkupKind.MARKDOWN
        value = content
    }
    return Hover().apply {
        contents = Either.forRight(markupContent)
    }
}

/**
 * Main entry point for creating hover from any AST node
 */
fun createHoverFor(node: ASTNode): GroovyParserResult<Hover> = buildHover {
    renderMarkdownContent(node.toMarkdownContent())
}.toGroovyParserResult()

private fun MarkdownBuilder.renderMarkdownContent(content: MarkdownContent) {
    when (content) {
        is MarkdownContent.Text -> text(content.value)
        is MarkdownContent.Code -> code(content.language, content.value)
        is MarkdownContent.Markdown -> markdown(content.value)
        is MarkdownContent.Section -> section(content.title) {
            content.content.forEach { renderMarkdownContent(it) }
        }

        is MarkdownContent.Header -> header(content.level, content.value)
        is MarkdownContent.List -> list(content.items)
        is MarkdownContent.KeyValue -> keyValue(content.pairs)
        is MarkdownContent.Table -> table(content.headers, content.rows)
        is MarkdownContent.Link -> link(content.text, content.url)
    }
}
