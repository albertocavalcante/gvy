package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
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
import kotlinx.coroutines.CancellationException
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

        return runCatching {
            createHoverForNode(coreNode)
        }.getOrElse { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Exception -> {
                    logger.debug("Error creating hover", throwable)
                    emptyHover()
                }

                else -> throw throwable
            }
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

    private fun createMethodHover(method: MethodDeclaration): String = markdown {
        code("groovy") {
            val params = method.parameters.joinToString(", ") { param ->
                "${param.type} ${param.name}"
            }
            "${method.returnType} ${method.name}($params)"
        }

        // Append GroovyDoc if present
        val doc = (method.comment as? JavadocComment)?.parse()
        if (doc != null) {
            val description = doc.description.toText().trim()
            if (description.isNotEmpty()) {
                text(description)
            }

            // @param tags
            val paramTags = doc.getParamTags()
            if (paramTags.isNotEmpty()) {
                text("**Parameters:**")
                list(paramTags.map { tag -> "`${tag.name}` - ${tag.content.toText()}" })
            }

            // @return tag
            doc.getReturnTag()?.let { returnTag ->
                text("**Returns:** ${returnTag.content.toText()}")
            }

            // @throws tags
            val throwsTags = doc.getThrowsTags()
            if (throwsTags.isNotEmpty()) {
                text("**Throws:**")
                list(throwsTags.map { tag -> "`${tag.name}` - ${tag.content.toText()}" })
            }
        }

        text("*(Method)*")
    }

    private fun createMethodCallHover(methodCall: MethodCallExpr): String = markdown {
        val methodName = methodCall.methodName

        // Try to resolve the method for richer info
        val resolvedSignature = runCatching {
            val argTypes = methodCall.arguments.mapNotNull { arg ->
                runCatching { resolver.resolveType(arg) }.getOrNull()
            }

            val methodRef = resolver.solveMethod(methodName, argTypes, methodCall)
            if (!methodRef.isSolved) {
                null
            } else {
                val resolved = methodRef.getDeclaration()
                val params = resolved.getParameters().joinToString(", ") { "${it.type.describe()} ${it.name}" }
                "${resolved.returnType.describe()} ${resolved.name}($params)"
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Exception -> {
                    logger.debug("Could not resolve method call {}", methodName, throwable)
                    null
                }

                else -> throw throwable
            }
        }

        if (resolvedSignature != null) {
            code("groovy", resolvedSignature)
            text("*(Method)*")
            return@markdown
        }

        // Fallback: show method name
        code("groovy", "$methodName(...)")
        text("*(Method call)*")
    }

    private fun createClassHover(classDecl: ClassDeclaration): String = markdown {
        val prefix = when {
            classDecl.isInterface -> "interface"
            classDecl.isEnum -> "enum"
            else -> "class"
        }
        code("groovy") {
            buildString {
                append(prefix).append(" ").append(classDecl.name)
                classDecl.superClass?.let { superType ->
                    append(" extends ").append(superType)
                }
                if (classDecl.implementedTypes.isNotEmpty()) {
                    append(" implements ").append(classDecl.implementedTypes.joinToString(", "))
                }
            }
        }
        text("*(${prefix.replaceFirstChar { it.uppercase() }})*")
    }

    private fun createFieldHover(field: FieldDeclaration): String = markdown {
        code("groovy", "${field.type} ${field.name}")
        text("*(Field)*")
    }

    private fun createConstructorHover(constructor: ConstructorDeclaration): String = markdown {
        code("groovy") {
            val params = constructor.parameters.joinToString(", ") { param ->
                "${param.type} ${param.name}"
            }
            "${constructor.name}($params)"
        }
        text("*(Constructor)*")
    }

    private fun createVariableHover(variable: VariableExpr): String = markdown {
        val name = variable.name

        // Try to resolve the symbol
        val resolvedSymbol = runCatching {
            val symbolRef = resolver.solveSymbol(name, variable)
            if (!symbolRef.isSolved) {
                null
            } else {
                symbolRef.getDeclaration()
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Exception -> {
                    logger.debug("Could not resolve symbol {}", name, throwable)
                    null
                }

                else -> throw throwable
            }
        }

        if (resolvedSymbol != null) {
            code("groovy", "${resolvedSymbol.type} $name")
            val kind = resolvedSymbol.javaClass.simpleName
                .replace("Resolved", "")
                .replace("Declaration", "")
            text("*($kind)*")
            return@markdown
        }

        // Fallback
        code("groovy", name)
        text("*(Variable)*")
    }

    private fun createParameterHover(param: Parameter): String = markdown {
        code("groovy", "${param.type} ${param.name}")
        text("*(Parameter)*")
    }

    private fun createGenericHover(node: Node): String = markdown {
        val nodeName = node::class.simpleName ?: "Node"
        code("groovy", node.toString())
        text("*($nodeName)*")
    }

    private fun emptyHover() = Hover(MarkupContent(MarkupKind.MARKDOWN, ""), null)
}
