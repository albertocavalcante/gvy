package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Renders expression nodes to markdown hover content.
 */
internal object ExpressionNodeRenderer {
    // Display limits for collection literals in hover content
    @Suppress("MagicNumber") // UI display constants
    private const val MAX_LIST_ELEMENTS_FULL_DISPLAY = 5
    private const val MAX_LIST_ELEMENTS_PREVIEW = 3
    private const val MAX_MAP_ENTRIES_FULL_DISPLAY = 3
    private const val MAX_MAP_ENTRIES_PREVIEW = 2

    fun MarkdownBuilder.renderVariableExpression(
        node: VariableExpression,
        moduleNode: ModuleNode?,
        semanticResolver: SemanticTypeResolver,
    ) {
        val type = if (node.type.isDynamic() && moduleNode != null) {
            semanticResolver.resolveType(node, moduleNode)
        } else {
            semanticResolver.toSemanticType(node.type)
        }

        val displayType = if (node.type.isDynamic()) {
            when (type) {
                is SemanticType.Dynamic, is SemanticType.Unknown -> "def"
                else -> semanticResolver.formatSemanticType(type)
            }
        } else {
            node.type.nameWithoutPackage
        }

        code("groovy") { "$displayType ${node.name}" }
    }

    fun MarkdownBuilder.renderDeclarationExpression(
        node: DeclarationExpression,
        moduleNode: ModuleNode?,
        semanticResolver: SemanticTypeResolver,
    ) {
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

    @Suppress("UnusedParameter", "FunctionParameterNaming") // TODO: Use _moduleNode for enhanced type resolution
    fun MarkdownBuilder.renderPropertyExpression(node: PropertyExpression, _moduleNode: ModuleNode?) {
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

    fun MarkdownBuilder.renderBinaryExpression(node: BinaryExpression) {
        section("Binary Expression") {
            code("groovy") { node.operation.text }
            keyValue(
                "Operator" to node.operation.text,
                "Left Type" to node.leftExpression.type.nameWithoutPackage,
                "Right Type" to node.rightExpression.type.nameWithoutPackage,
            )
        }
    }

    fun MarkdownBuilder.renderClosureExpression(node: ClosureExpression) {
        section("Closure") {
            code("groovy") { "{ ${node.parametersString()} -> ... }" }
            val variablesInScope = node.variableScope
                ?.declaredVariables
                ?.keys
                ?.joinToString(", ")
                ?: ""
            keyValue(
                "Parameters" to node.parametersString(),
                "Variables in Scope" to variablesInScope,
            )
        }
    }

    fun MarkdownBuilder.renderListExpression(node: ListExpression) {
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

    fun MarkdownBuilder.renderMapExpression(node: MapExpression) {
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

    fun MarkdownBuilder.renderConstantExpression(node: ConstantExpression) {
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

    fun MarkdownBuilder.renderGStringExpression(node: GStringExpression) {
        section("GString") {
            code("groovy") { node.text }
            text("Interpolated string expression")
        }
    }

    private fun ClosureExpression.parametersString(): String =
        parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""
}
