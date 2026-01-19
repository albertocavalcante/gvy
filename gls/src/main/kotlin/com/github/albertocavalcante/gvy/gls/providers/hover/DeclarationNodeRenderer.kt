package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.gvy.gls.documentation.SignatureFormatter
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import java.lang.reflect.Modifier

/**
 * Renders declaration nodes (methods, classes, fields, properties, parameters) to markdown hover content.
 */
internal object DeclarationNodeRenderer {
    private const val MAX_DISPLAYED_ITEMS = 5

    fun MarkdownBuilder.renderMethodNode(node: MethodNode) {
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

    fun MarkdownBuilder.renderClassNode(node: ClassNode) {
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

    fun MarkdownBuilder.renderFieldNode(node: FieldNode) {
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

    fun MarkdownBuilder.renderPropertyNode(node: PropertyNode) {
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

    fun MarkdownBuilder.renderParameter(node: Parameter) {
        section("Parameter") {
            code("groovy") { SignatureFormatter.formatParameter(node) }
            node.initialExpression?.let {
                keyValue("Default Value" to it.text)
            }
        }
    }

    private fun modifiersString(node: ASTNode): String = buildString {
        val modifiers = when (node) {
            is MethodNode -> node.modifiers
            is FieldNode -> node.modifiers
            is ClassNode -> node.modifiers
            is PropertyNode -> node.modifiers
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
}
