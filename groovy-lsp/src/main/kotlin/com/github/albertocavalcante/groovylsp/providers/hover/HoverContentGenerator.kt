package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.documentation.SignatureFormatter
import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import com.github.albertocavalcante.groovyparser.errors.toGroovyParserResult
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/**
 * Service to generate hover content for AST nodes using SemanticTypeResolver.
 * Replaces static extension functions in HoverNodeConverters.kt.
 */
class HoverContentGenerator(private val semanticResolver: SemanticTypeResolver) {
    companion object {
        private const val MAX_DISPLAYED_ITEMS = 5
        private val logger = LoggerFactory.getLogger(HoverContentGenerator::class.java)

        // Display limits for collection literals in hover content
        @Suppress("MagicNumber") // UI display constants
        private const val MAX_LIST_ELEMENTS_FULL_DISPLAY = 5
        private const val MAX_LIST_ELEMENTS_PREVIEW = 3
        private const val MAX_MAP_ENTRIES_FULL_DISPLAY = 3
        private const val MAX_MAP_ENTRIES_PREVIEW = 2
    }

    /**
     * Generate hover for an AST node.
     */
    fun generateHover(node: ASTNode, moduleNode: ModuleNode? = null): GroovyParserResult<Hover> {
        val content = markdown {
            renderNode(node, moduleNode)
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = content
        }

        return Hover().apply {
            contents = Either.forRight(markupContent)
        }.toGroovyParserResult()
    }

    private fun MarkdownBuilder.renderNode(node: ASTNode, moduleNode: ModuleNode?) {
        when {
            node.isDeclarationNode() -> renderDeclarationNode(node, moduleNode)
            node.isExpressionNode() -> renderExpressionNode(node, moduleNode)
            node.isMetadataNode() -> renderMetadataNode(node)
            else -> defaultRender(node)
        }
    }

    @Suppress("UNUSED_PARAMETER") // moduleNode reserved for future semantic resolution in properties/fields
    private fun MarkdownBuilder.renderDeclarationNode(node: ASTNode, moduleNode: ModuleNode?) = when (node) {
        is MethodNode -> renderMethodNode(node)
        is ClassNode -> renderClassNode(node)
        is FieldNode -> renderFieldNode(node)
        is PropertyNode -> renderPropertyNode(node)
        is Parameter -> renderParameter(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.renderExpressionNode(node: ASTNode, moduleNode: ModuleNode?) = when (node) {
        is VariableExpression -> renderVariableExpression(node, moduleNode)
        is DeclarationExpression -> renderDeclarationExpression(node, moduleNode)
        is MethodCallExpression -> renderMethodCallExpression(node)
        is ConstructorCallExpression -> renderConstructorCallExpression(node)
        is PropertyExpression -> renderPropertyExpression(node, moduleNode)
        is BinaryExpression -> renderBinaryExpression(node)
        is ClosureExpression -> renderClosureExpression(node)
        is ListExpression -> renderListExpression(node)
        is MapExpression -> renderMapExpression(node)
        is ConstantExpression -> renderConstantExpression(node)
        is GStringExpression -> renderGStringExpression(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.renderMetadataNode(node: ASTNode) = when (node) {
        is ImportNode -> renderImportNode(node)
        is PackageNode -> renderPackageNode(node)
        is AnnotationNode -> renderAnnotationNode(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.defaultRender(node: ASTNode) {
        section("AST Node") {
            text(node::class.java.simpleName)
            code { node.toString() }
        }
    }

    // --- Specific Renderers ---

    private fun MarkdownBuilder.renderVariableExpression(node: VariableExpression, moduleNode: ModuleNode?) {
        val type = if (node.type.isDynamic() && moduleNode != null) {
            semanticResolver.resolveType(node, moduleNode)
        } else {
            semanticResolver.toSemanticType(node.type)
        }

        val displayType = if (node.type.isDynamic()) {
            if (type is SemanticType.Dynamic ||
                type is SemanticType.Unknown
            ) {
                "def"
            } else {
                semanticResolver.formatSemanticType(type)
            }
        } else {
            node.type.nameWithoutPackage
        }

        code("groovy") { "$displayType ${node.name}" }
    }

    private fun MarkdownBuilder.renderMethodNode(node: MethodNode) {
        section("Method") {
            code("groovy") { SignatureFormatter.formatMethod(node) }
            keyValue(
                "Return Type" to (node.returnType?.nameWithoutPackage ?: "def"),
                "Modifiers" to modifiersString(node),
                "Owner" to (node.declaringClass?.nameWithoutPackage ?: "unknown"),
            )
            node.groovydoc?.let { doc ->
                if (doc.content.isNotBlank()) {
                    markdown(doc.content)
                }
            }
        }
    }

    private fun MarkdownBuilder.renderClassNode(node: ClassNode) {
        section("Class") {
            code("groovy") { SignatureFormatter.formatClass(node) }

            if (node.methods.isNotEmpty()) {
                section("Methods") {
                    list(node.methods.take(MAX_DISPLAYED_ITEMS).map { "${it.name}(${parametersString(it)})" })
                    if (node.methods.size > MAX_DISPLAYED_ITEMS) {
                        text("... and ${node.methods.size - MAX_DISPLAYED_ITEMS} more")
                    }
                }
            }

            if (node.fields.isNotEmpty()) {
                section("Fields") {
                    list(node.fields.take(MAX_DISPLAYED_ITEMS).map { "${it.type.nameWithoutPackage} ${it.name}" })
                    if (node.fields.size > MAX_DISPLAYED_ITEMS) {
                        text("... and ${node.fields.size - MAX_DISPLAYED_ITEMS} more")
                    }
                }
            }
        }
    }

    // TODO(#641): Add additional renderers following the pattern from HoverNodeConverters.kt

    private fun MarkdownBuilder.renderDeclarationExpression(node: DeclarationExpression, moduleNode: ModuleNode?) {
        val varExpr = node.leftExpression as? VariableExpression
        val name = varExpr?.name ?: "unknown"

        val type = if (moduleNode != null) {
            semanticResolver.resolveType(node.rightExpression, moduleNode)
        } else {
            semanticResolver.toSemanticType(node.leftExpression.type)
        }
        val typeName = semanticResolver.formatSemanticType(type)
        val displayType = typeName.substringAfterLast('.')

        section("Variable Declaration") {
            code("groovy") { "$displayType $name" }
            keyValue(
                "Inferred Type" to typeName,
                "Name" to name,
                "Initial Value" to node.rightExpression.text,
            )
        }
    }

    private fun MarkdownBuilder.renderFieldNode(node: FieldNode) {
        section("Field") {
            code("groovy") { SignatureFormatter.formatField(node) }
            keyValue(
                "Type" to node.type.nameWithoutPackage,
                "Modifiers" to modifiersString(node),
                "Owner" to (node.declaringClass?.nameWithoutPackage ?: "unknown"),
            )
            node.initialExpression?.let {
                keyValue("Initial Value" to it.text)
            }
        }
    }

    private fun MarkdownBuilder.renderPropertyNode(node: PropertyNode) {
        section("Property") {
            code("groovy") { "${node.type.nameWithoutPackage} ${node.name}" }
            keyValue(
                "Type" to node.type.nameWithoutPackage,
                "Modifiers" to modifiersString(node),
                "Owner" to (node.declaringClass?.nameWithoutPackage ?: "unknown"),
                "Getter" to if (node.getterBlock != null) "available" else "none",
                "Setter" to if (node.setterBlock != null) "available" else "none",
            )
        }
    }

    private fun MarkdownBuilder.renderParameter(node: Parameter) {
        section("Parameter") {
            code("groovy") { SignatureFormatter.formatParameter(node) }
            node.initialExpression?.let {
                keyValue("Default Value" to it.text)
            }
        }
    }

    private fun MarkdownBuilder.renderMethodCallExpression(node: MethodCallExpression) {
        val methodName = node.displayMethodName()
        val receiver = node.displayReceiver()
        val arguments = node.displayArguments()
        val callOperator = node.displayCallOperator()

        val signature = buildString {
            if (!node.isImplicitThis) {
                append(receiver)
                append(callOperator)
            }
            append(methodName)
            append("(")
            append(arguments)
            append(")")
        }

        section("Method Call") {
            code("groovy") { signature }
            keyValue(
                "Method" to methodName,
                "Receiver" to receiver,
                "Arguments" to arguments.ifBlank { "none" },
            )

            val methodTarget = node.nodeMetaData["targetMethod"] as? MethodNode
            if (methodTarget != null) {
                markdown("**Resolved Target**")
                code("groovy") { SignatureFormatter.formatMethod(methodTarget) }
                keyValue("Owner" to (methodTarget.declaringClass?.nameWithoutPackage ?: "unknown"))
            }
        }
    }

    private fun MarkdownBuilder.renderConstructorCallExpression(node: ConstructorCallExpression) {
        val className = node.type.nameWithoutPackage
        val arguments = displayConstructorArguments(node)

        val signature = buildString {
            append("new ")
            append(className)
            append("(")
            append(arguments)
            append(")")
        }

        section("Constructor Call") {
            code("groovy") { signature }
            keyValue(
                "Class" to className,
                "Arguments" to arguments.ifBlank { "none" },
            )

            // Show if it's a special call (this() or super())
            if (node.isSuperCall) {
                keyValue("Type" to "super() call")
            } else if (node.isThisCall) {
                keyValue("Type" to "this() call")
            }

            // Show full class name if different from simple name
            val fullClassName = node.type.name
            if (fullClassName != className) {
                keyValue("Full Name" to fullClassName)
            }
        }
    }

    private fun displayConstructorArguments(node: ConstructorCallExpression): String {
        val arguments = node.arguments
        return when (arguments) {
            is ArgumentListExpression -> arguments.expressions.map { it.displayArgument() }
            is TupleExpression -> arguments.expressions.map { it.displayArgument() }
            is MapExpression -> arguments.mapEntryExpressions.map { it.displayNamedArgument() }
            else -> listOf(arguments.text.takeIf { it.isNotBlank() } ?: "")
        }.filter { it.isNotBlank() }.joinToString(", ")
    }

    @Suppress("UnusedParameter", "FunctionParameterNaming") // TODO: Use _moduleNode for enhanced type resolution
    private fun MarkdownBuilder.renderPropertyExpression(node: PropertyExpression, _moduleNode: ModuleNode?) {
        val propertyName = node.propertyAsString ?: node.property.text
        val objectExpr = node.objectExpression
        val objectType = objectExpr.type.nameWithoutPackage

        val displayExpr = buildString {
            append(objectExpr.text.takeIf { it.isNotBlank() } ?: "this")
            append(if (node.isSafe) "?." else ".")
            append(propertyName)
        }

        section("Property Access") {
            code("groovy") { displayExpr }
            keyValue(
                "Property" to propertyName,
                "Object Type" to objectType,
                "Safe Navigation" to node.isSafe.toString(),
            )

            // Try to resolve the property type
            val propertyType = node.type.nameWithoutPackage
            if (propertyType != "java.lang.Object") {
                keyValue("Property Type" to propertyType)
            }
        }
    }

    private fun MarkdownBuilder.renderBinaryExpression(node: BinaryExpression) {
        section("Binary Expression") {
            code("groovy") { node.operation.text }
            keyValue(
                "Operator" to node.operation.text,
                "Left Type" to node.leftExpression.type.nameWithoutPackage,
                "Right Type" to node.rightExpression.type.nameWithoutPackage,
            )
        }
    }

    private fun MarkdownBuilder.renderClosureExpression(node: ClosureExpression) {
        section("Closure") {
            code("groovy") { "{ ${node.parametersString()} -> ... }" }
            keyValue(
                "Parameters" to node.parametersString(),
                "Variables in Scope" to node.variableScope.declaredVariables.keys.joinToString(", "),
            )
        }
    }

    private fun MarkdownBuilder.renderConstantExpression(node: ConstantExpression) {
        val typeDescription = when (node.type.name) {
            "java.lang.String" -> "String literal"
            "java.lang.Integer", "int" -> "Integer literal"
            "java.lang.Double", "double" -> "Double literal"
            "java.lang.Boolean", "boolean" -> "Boolean literal"
            else -> "Constant"
        }

        section(typeDescription) {
            code("groovy") { node.text }
            keyValue(
                "Value" to (node.value?.toString() ?: "null"),
                "Type" to node.type.nameWithoutPackage,
            )
        }
    }

    private fun MarkdownBuilder.renderGStringExpression(node: GStringExpression) {
        section("GString") {
            code("groovy") { node.text }
            text("Interpolated string expression")
        }
    }

    private fun MarkdownBuilder.renderListExpression(node: ListExpression) {
        val elementCount = node.expressions.size
        val listType = node.type.nameWithoutPackage

        section("List Literal") {
            code("groovy") {
                if (elementCount <= MAX_LIST_ELEMENTS_FULL_DISPLAY) {
                    "[${node.expressions.joinToString(", ") { it.text }}]"
                } else {
                    "[${node.expressions.take(MAX_LIST_ELEMENTS_PREVIEW).joinToString(", ") { it.text }}, ...]"
                }
            }
            keyValue(
                "Type" to listType,
                "Size" to elementCount.toString(),
            )

            if (elementCount > 0) {
                val elementTypes = node.expressions
                    .map { it.type.nameWithoutPackage }
                    .distinct()
                    .joinToString(", ")
                keyValue("Element Types" to elementTypes)
            }
        }
    }

    private fun MarkdownBuilder.renderMapExpression(node: MapExpression) {
        val entryCount = node.mapEntryExpressions.size
        val mapType = node.type.nameWithoutPackage

        section("Map Literal") {
            code("groovy") {
                if (entryCount == 0) {
                    "[:]"
                } else if (entryCount <= MAX_MAP_ENTRIES_FULL_DISPLAY) {
                    "[${node.mapEntryExpressions.joinToString(", ") {
                        "${it.keyExpression.text}: ${it.valueExpression.text}"
                    }}]"
                } else {
                    "[${node.mapEntryExpressions.take(MAX_MAP_ENTRIES_PREVIEW).joinToString(", ") {
                        "${it.keyExpression.text}: ${it.valueExpression.text}"
                    }}, ...]"
                }
            }
            keyValue(
                "Type" to mapType,
                "Size" to entryCount.toString(),
            )

            if (entryCount > 0) {
                val keyTypes = node.mapEntryExpressions
                    .map { it.keyExpression.type.nameWithoutPackage }
                    .distinct()
                    .joinToString(", ")
                val valueTypes = node.mapEntryExpressions
                    .map { it.valueExpression.type.nameWithoutPackage }
                    .distinct()
                    .joinToString(", ")

                keyValue(
                    "Key Types" to keyTypes,
                    "Value Types" to valueTypes,
                )
            }
        }
    }

    private fun MarkdownBuilder.renderImportNode(node: ImportNode) {
        val formatted = formatImport(node)
        logger.debug("formatImport result: '$formatted'")
        section("Import") {
            code("groovy") { formatted }
            keyValue(
                "Class" to node.className,
                "Alias" to (node.alias ?: "none"),
                "Package" to (node.packageName ?: "default"),
                "Star Import" to node.isStar.toString(),
            )
        }
    }

    private fun formatImport(node: ImportNode): String = buildString {
        append("import ")
        if (node.isStatic) append("static ")
        append(node.className)
        if (node.isStatic && node.fieldName != null && !node.isStar) {
            append(".${node.fieldName}")
        }
        if (node.isStar) append(".*")

        val simpleClassName = node.className.substringAfterLast('.')
        node.alias?.let {
            if (it != simpleClassName) {
                append(" as $it")
            }
        }
    }

    private fun MarkdownBuilder.renderPackageNode(node: PackageNode) {
        section("Package") {
            code("groovy") { "package ${node.name}" }
        }
    }

    private fun MarkdownBuilder.renderAnnotationNode(node: AnnotationNode) {
        section("Annotation") {
            code("groovy") { "@${node.classNode.nameWithoutPackage}" }
            if (node.members.isNotEmpty()) {
                val members = node.members.entries.joinToString(", ") { "${it.key}: ${it.value.text}" }
                keyValue("Members" to members)
            }
        }
    }

    // Helpers (moved from HoverNodeConverters and adapted)

    private fun signature(node: MethodNode): String = buildString {
        append(modifiersString(node)).append(" ")
        append(node.returnType?.nameWithoutPackage ?: "def").append(" ")
        append(node.name).append("(")
        append(parametersString(node))
        append(")")
    }

    private fun modifiersString(node: ASTNode): String = buildString {
        val modifiers = when (node) {
            is MethodNode -> node.modifiers
            is FieldNode -> node.modifiers
            is ClassNode -> node.modifiers
            else -> 0
        }
        val parts = mutableListOf<String>()
        if (Modifier.isPublic(modifiers)) parts += "public"
        if (Modifier.isPrivate(modifiers)) parts += "private"
        if (Modifier.isProtected(modifiers)) parts += "protected"
        if (Modifier.isStatic(modifiers)) parts += "static"
        if (Modifier.isFinal(modifiers)) parts += "final"
        if (Modifier.isAbstract(modifiers)) parts += "abstract"
        append(parts.joinToString(" "))
    }

    private fun parametersString(node: MethodNode): String =
        node.parameters.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" }

    private fun ClosureExpression.parametersString(): String =
        parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""

    private fun MethodCallExpression.displayMethodName(): String =
        methodAsString ?: method.text.takeUnless { it.isNullOrBlank() } ?: "<dynamic>"

    private fun MethodCallExpression.displayReceiver(): String = when {
        isImplicitThis -> "this"
        else -> objectExpression?.text?.takeUnless { it.isBlank() } ?: "this"
    }

    private fun MethodCallExpression.displayCallOperator(): String = when {
        isSafe -> "?."
        isSpreadSafe -> "*."
        else -> "."
    }

    private fun MethodCallExpression.displayArguments(): String {
        val values = when (val expression = arguments) {
            is ArgumentListExpression -> expression.expressions.map { it.displayArgument() }
            is TupleExpression -> expression.expressions.map { it.displayArgument() }
            is MapExpression -> expression.mapEntryExpressions.map { it.displayNamedArgument() }
            else -> listOf(expression.text.takeIf { it.isNotBlank() } ?: "")
        }

        return values.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun Expression.displayArgument(): String = when (this) {
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

    // Node Classification Helpers
    private fun ASTNode.isDeclarationNode(): Boolean = this is MethodNode || this is ClassNode ||
        this is FieldNode || this is PropertyNode || this is Parameter

    private fun ASTNode.isExpressionNode(): Boolean = this is VariableExpression || this is MethodCallExpression ||
        this is ConstructorCallExpression || this is PropertyExpression || this is BinaryExpression ||
        this is DeclarationExpression || this is ClosureExpression || this is ListExpression ||
        this is MapExpression || this is ConstantExpression || this is GStringExpression

    private fun ASTNode.isMetadataNode(): Boolean = this is ImportNode || this is PackageNode || this is AnnotationNode
}
