package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovyparser.ast.JavadocComment
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.resolution.GroovySymbolResolver
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Hover provider for the Core (JavaParser-style) parser engine.
 *
 * Uses [GroovySymbolResolver] to resolve types and provide rich hover information
 * including method signatures, return types, and parameter info.
 */
class CoreHoverProvider(private val parseUnit: ParseUnit, private val typeSolver: TypeSolver) : HoverProvider {

    private val logger = LoggerFactory.getLogger(CoreHoverProvider::class.java)
    private val resolver by lazy { GroovySymbolResolver(typeSolver) }

    override suspend fun getHover(params: HoverParams): Hover {
        val unifiedNode = parseUnit.nodeAt(params.position)
            ?: return emptyHover()

        val coreNode = unifiedNode.originalNode as? Node
            ?: return emptyHover()

        return try {
            createHoverForNode(coreNode)
        } catch (e: Exception) {
            logger.debug("Error creating hover: ${e.message}")
            emptyHover()
        }
    }

    private fun createHoverForNode(node: Node): Hover {
        val content = when (node) {
            is MethodDeclaration -> createMethodHover(node)
            is MethodCallExpr -> createMethodCallHover(node)
            is ClassDeclaration -> createClassHover(node)
            is FieldDeclaration -> createFieldHover(node)
            is ConstructorDeclaration -> createConstructorHover(node)
            is VariableExpr -> createVariableHover(node)
            is Parameter -> createParameterHover(node)
            else -> createGenericHover(node)
        }

        val range = node.range?.let {
            Range(
                Position(it.begin.line - 1, it.begin.column - 1),
                Position(it.end.line - 1, it.end.column),
            )
        }

        return Hover(MarkupContent(MarkupKind.MARKDOWN, content), range)
    }

    private fun createMethodHover(method: MethodDeclaration): String = buildString {
        append("```groovy\n")
        // Return type and method name
        append(method.returnType).append(" ")
        append(method.name).append("(")
        // Parameters
        append(
            method.parameters.joinToString(", ") { param ->
                "${param.type} ${param.name}"
            },
        )
        append(")")
        append("\n```")

        // Append GroovyDoc if present
        val doc = (method.comment as? JavadocComment)?.parse()
        if (doc != null) {
            val description = doc.description.toText().trim()
            if (description.isNotEmpty()) {
                append("\n\n").append(description)
            }

            // @param tags
            val paramTags = doc.getParamTags()
            if (paramTags.isNotEmpty()) {
                append("\n\n**Parameters:**\n")
                paramTags.forEach { tag ->
                    append("- `${tag.name}` - ${tag.content.toText()}\n")
                }
            }

            // @return tag
            doc.getReturnTag()?.let { returnTag ->
                append("\n**Returns:** ${returnTag.content.toText()}")
            }

            // @throws tags
            val throwsTags = doc.getThrowsTags()
            if (throwsTags.isNotEmpty()) {
                append("\n\n**Throws:**\n")
                throwsTags.forEach { tag ->
                    append("- `${tag.name}` - ${tag.content.toText()}\n")
                }
            }
        }

        append("\n\n*(Method)*")
    }

    private fun createMethodCallHover(methodCall: MethodCallExpr): String = buildString {
        val methodName = methodCall.methodName

        // Try to resolve the method for richer info
        try {
            val argTypes = methodCall.arguments.mapNotNull { arg ->
                try {
                    resolver.resolveType(arg)
                } catch (e: Exception) {
                    null
                }
            }

            val methodRef = resolver.solveMethod(methodName, argTypes, methodCall)
            if (methodRef.isSolved) {
                val resolved = methodRef.getDeclaration()
                append("```groovy\n")
                append(resolved.returnType.describe()).append(" ")
                append(resolved.name).append("(")
                append(resolved.getParameters().joinToString(", ") { "${it.type.describe()} ${it.name}" })
                append(")")
                append("\n```")
                append("\n\n*(Method)*")
                return@buildString
            }
        } catch (e: Exception) {
            logger.debug("Could not resolve method call {}: {}", methodName, e.message)
        }

        // Fallback: show method name
        append("```groovy\n")
        append(methodName).append("(...)")
        append("\n```")
        append("\n\n*(Method call)*")
    }

    private fun createClassHover(classDecl: ClassDeclaration): String = buildString {
        append("```groovy\n")
        val prefix = when {
            classDecl.isInterface -> "interface"
            classDecl.isEnum -> "enum"
            else -> "class"
        }
        append(prefix).append(" ").append(classDecl.name)
        classDecl.superClass?.let { superType ->
            append(" extends ").append(superType)
        }
        if (classDecl.implementedTypes.isNotEmpty()) {
            append(" implements ").append(classDecl.implementedTypes.joinToString(", "))
        }
        append("\n```")
        append("\n\n*(${prefix.replaceFirstChar { it.uppercase() }})*")
    }

    private fun createFieldHover(field: FieldDeclaration): String = buildString {
        append("```groovy\n")
        append(field.type).append(" ")
        append(field.name)
        append("\n```")
        append("\n\n*(Field)*")
    }

    private fun createConstructorHover(constructor: ConstructorDeclaration): String = buildString {
        append("```groovy\n")
        append(constructor.name).append("(")
        append(
            constructor.parameters.joinToString(", ") { param ->
                "${param.type} ${param.name}"
            },
        )
        append(")")
        append("\n```")
        append("\n\n*(Constructor)*")
    }

    private fun createVariableHover(variable: VariableExpr): String = buildString {
        val name = variable.name

        // Try to resolve the symbol
        try {
            val symbolRef = resolver.solveSymbol(name, variable)
            if (symbolRef.isSolved) {
                val resolved = symbolRef.getDeclaration()
                append("```groovy\n")
                append(resolved.type).append(" ").append(name)
                append("\n```")
                val kind = resolved.javaClass.simpleName
                    .replace("Resolved", "")
                    .replace("Declaration", "")
                append("\n\n*($kind)*")
                return@buildString
            }
        } catch (e: Exception) {
            logger.debug("Could not resolve symbol {}: {}", name, e.message)
        }

        // Fallback
        append("```groovy\n")
        append(name)
        append("\n```")
        append("\n\n*(Variable)*")
    }

    private fun createParameterHover(param: Parameter): String = buildString {
        append("```groovy\n")
        append(param.type).append(" ").append(param.name)
        append("\n```")
        append("\n\n*(Parameter)*")
    }

    private fun createGenericHover(node: Node): String = buildString {
        val nodeName = node::class.simpleName ?: "Node"
        append("```groovy\n")
        append(node)
        append("\n```")
        append("\n\n*($nodeName)*")
    }

    private fun emptyHover() = Hover(MarkupContent(MarkupKind.MARKDOWN, ""), null)
}
