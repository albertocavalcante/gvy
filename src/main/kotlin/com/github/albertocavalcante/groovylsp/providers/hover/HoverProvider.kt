package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.ast.findNodeAt
import com.github.albertocavalcante.groovylsp.ast.getDocumentation
import com.github.albertocavalcante.groovylsp.ast.isHoverable
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.ast.toHoverString
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.InvalidPositionException
import com.github.albertocavalcante.groovylsp.errors.NodeNotFoundAtPositionException
import com.github.albertocavalcante.groovylsp.errors.SymbolResolutionException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI

/**
 * Kotlin-idiomatic hover provider for Groovy symbols.
 * Uses coroutines, extension functions, and null safety for clean async processing.
 */
class HoverProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)

    /**
     * Provide hover information for the symbol at the given position.
     * Returns null if no hover information is available.
     */
    suspend fun provideHover(uri: String, position: Position): Hover? = withContext(Dispatchers.Default) {
        try {
            logger.debug("Providing hover for $uri at ${position.line}:${position.character}")

            val documentUri = URI.create(uri)

            // Try to get the AST visitor and symbol table for enhanced functionality
            val astVisitor = compilationService.getAstVisitor(documentUri)
            val symbolTable = compilationService.getSymbolTable(documentUri)

            val nodeAtPosition = if (astVisitor != null) {
                // Use enhanced AST visitor-based approach
                astVisitor.getNodeAt(documentUri, position)
            } else {
                // Fallback to original AST-based approach
                val ast = compilationService.getAst(documentUri) as? ModuleNode
                ast?.findNodeAt(position.line, position.character)
            } ?: return@withContext null

            logger.debug("Found node at position: ${nodeAtPosition.javaClass.simpleName}")

            // For hover, we usually want the node the user is actually hovering over,
            // not its definition (except for references to see what they point to)
            val definitionNode = if (astVisitor != null && symbolTable != null) {
                when (nodeAtPosition) {
                    // For variable references, resolve to definition to show what they point to
                    is VariableExpression -> {
                        // But only if it's not part of a declaration
                        val parent = astVisitor.getParent(nodeAtPosition)
                        if (parent is org.codehaus.groovy.ast.expr.DeclarationExpression &&
                            parent.leftExpression == nodeAtPosition
                        ) {
                            // This is a variable being declared, show the declaration itself
                            nodeAtPosition
                        } else {
                            // This is a variable reference, resolve to definition
                            nodeAtPosition.resolveToDefinition(
                                astVisitor,
                                symbolTable,
                                strict = false,
                            ) ?: nodeAtPosition
                        }
                    }
                    // For most other nodes (constants, GStrings, etc.), show the node itself
                    else -> nodeAtPosition
                }
            } else {
                nodeAtPosition
            }

            // Only provide hover for hoverable nodes
            if (!definitionNode.isHoverable()) {
                return@withContext null
            }

            // Create hover content using the definition node (or original node if no definition found)
            createHover(definitionNode)
        } catch (e: NodeNotFoundAtPositionException) {
            logger.debug("No node found at position for hover: $e")
            null
        } catch (e: InvalidPositionException) {
            logger.warn("Invalid position for hover: $e")
            null
        } catch (e: SymbolResolutionException) {
            logger.debug("Symbol resolution failed for hover: $e")
            null
        } catch (e: IllegalStateException) {
            logger.error("Error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: IllegalArgumentException) {
            val documentUri = URI.create(uri)
            val specificException = documentUri.invalidPosition(
                position.line,
                position.character,
                e.message ?: "Invalid arguments",
            )
            logger.error("Invalid arguments for hover: $specificException", e)
            null
        } catch (e: GroovyLspException) {
            logger.error("LSP error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: IOException) {
            logger.error("I/O error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        } catch (e: RuntimeException) {
            logger.error("Runtime error providing hover for $uri at ${position.line}:${position.character}", e)
            null
        }
    }

    /**
     * Create a hover object with formatted content.
     */
    private fun createHover(node: ASTNode): Hover = Hover().apply {
        contents = Either.forRight(
            MarkupContent().apply {
                kind = MarkupKind.MARKDOWN
                value = buildHoverContent(node)
            },
        )
    }

    /**
     * Build the markdown content for the hover.
     * Uses Kotlin's buildString DSL for efficient string construction.
     */
    private fun buildHoverContent(node: ASTNode): String {
        val result = buildString {
            // Add the formatted symbol signature
            appendLine("```groovy")
            appendLine(node.formatForHover())
            appendLine("```")

            // Add documentation if available
            (node as? AnnotatedNode)?.getDocumentation()?.let { doc ->
                appendLine()
                appendLine("---")
                appendLine()
                append(doc)
            }

            // Add additional context information
            addContextualInfo(node)?.let { contextInfo ->
                appendLine()
                appendLine()
                append(contextInfo)
            }
        }
        return result
    }

    /**
     * Format an AST node for hover display using extension functions.
     * Uses when expression for type-safe pattern matching.
     */
    private fun ASTNode.formatForHover(): String = when (this) {
        is MethodNode -> toHoverString()
        is Variable -> toHoverString()
        is ClassNode -> toHoverString()
        is FieldNode -> toHoverString()
        is PropertyNode -> toHoverString()
        is VariableExpression -> formatVariableExpression()
        is ConstantExpression -> formatConstantExpression()
        is MethodCallExpression -> formatMethodCallExpression()
        is DeclarationExpression -> formatDeclarationExpression()
        is BinaryExpression -> formatBinaryExpression()
        is Parameter -> formatParameter()
        is ClosureExpression -> formatClosureExpression()
        is GStringExpression -> formatGStringExpression()
        is ImportNode -> formatImportNode()
        is PackageNode -> formatPackageNode()
        is AnnotationNode -> formatAnnotationNode()
        else -> "Symbol: ${javaClass.simpleName}"
    }

    /**
     * Format a variable expression for hover display.
     */
    private fun VariableExpression.formatVariableExpression(): String {
        val typeName = if (type.name == "java.lang.Object") "def" else type.nameWithoutPackage
        return "$typeName $name"
    }

    /**
     * Format a declaration expression for hover display.
     */
    private fun DeclarationExpression.formatDeclarationExpression(): String {
        val varExpr = leftExpression as? VariableExpression
        return if (varExpr != null) {
            "${varExpr.type.nameWithoutPackage} ${varExpr.name}"
        } else {
            "Variable declaration"
        }
    }

    /**
     * Format a binary expression for hover display.
     */
    private fun BinaryExpression.formatBinaryExpression(): String = when (operation.text) {
        "=" -> "Assignment: ${leftExpression.text} = ${rightExpression.text}"
        else -> "Operation: ${operation.text}"
    }

    /**
     * Format a parameter for hover display.
     */
    private fun Parameter.formatParameter(): String = "${type.nameWithoutPackage} $name"

    /**
     * Format a closure expression for hover display.
     */
    private fun ClosureExpression.formatClosureExpression(): String {
        val params = parameters?.joinToString(", ") { param ->
            "${param.type.nameWithoutPackage} ${param.name}"
        } ?: ""
        return if (params.isEmpty()) {
            "Closure { ... }"
        } else {
            "Closure { $params -> ... }"
        }
    }

    /**
     * Format a GString expression for hover display.
     */
    private fun GStringExpression.formatGStringExpression(): String {
        val parts = mutableListOf<String>()
        val stringParts = strings ?: emptyList()
        val valueParts = values ?: emptyList()

        // Reconstruct the GString representation
        for (i in stringParts.indices) {
            val stringPart = stringParts[i]
            if (stringPart is ConstantExpression) {
                parts.add(stringPart.value?.toString() ?: "")
            }
            if (i < valueParts.size) {
                val valueExpr = valueParts[i]
                val varName = when (valueExpr) {
                    is VariableExpression -> valueExpr.name
                    is PropertyExpression -> valueExpr.propertyAsString
                    is MethodCallExpression -> "${valueExpr.methodAsString}(...)"
                    else -> "expression"
                }
                parts.add("\${$varName}")
            }
        }

        return "GString \"${parts.joinToString("")}\""
    }

    /**
     * Format an import node for hover display.
     */
    private fun ImportNode.formatImportNode(): String {
        val alias = alias
        return when {
            isStatic && isStar -> "import static $className.*"
            isStatic && alias != null -> "import static $className.$fieldName as $alias"
            isStatic -> "import static $className.$fieldName"
            isStar -> "import $className.*"
            alias != null -> "import $className as $alias"
            else -> "import $className"
        }
    }

    /**
     * Format a package node for hover display.
     */
    private fun PackageNode.formatPackageNode(): String = "package $name"

    /**
     * Format an annotation node for hover display.
     */
    private fun AnnotationNode.formatAnnotationNode(): String = "@${classNode.nameWithoutPackage}"

    /**
     * Add contextual information based on the node type.
     * Provides additional helpful details about the symbol.
     */
    private fun addContextualInfo(node: ASTNode): String? = when (node) {
        is MethodNode -> buildMethodContext(node)
        is ClassNode -> buildClassContext(node)
        is FieldNode -> buildFieldContext(node)
        is PropertyNode -> buildPropertyContext(node)
        else -> null
    }

    /**
     * Build contextual information for methods.
     */
    private fun buildMethodContext(method: MethodNode): String? = buildString {
        // Show declaring class
        val declaringClass = method.declaringClass
        when {
            declaringClass?.isScript == true -> {
                append("**Type:** Script method")
                appendLine()
            }
            declaringClass != null -> {
                append("**Declared in:** ${declaringClass.nameWithoutPackage}")
                appendLine()
            }
        }

        // Show parameter count
        if (method.parameters.isNotEmpty()) {
            append("**Parameters:** ${method.parameters.size}")
            appendLine()
        }

        // Show if it's a constructor
        if (method.isConstructor) {
            append("**Type:** Constructor")
            appendLine()
        }

        // Show override information
        if (method.isAbstract) {
            append("**Modifier:** Abstract method")
        }
    }.takeIf { it.isNotBlank() }

    /**
     * Build contextual information for classes.
     */
    private fun buildClassContext(classNode: ClassNode): String? = buildString {
        // Show member counts
        val methodCount = classNode.methods.size
        val fieldCount = classNode.fields.size

        if (methodCount > 0) {
            append("**Methods:** $methodCount")
            appendLine()
        }

        if (fieldCount > 0) {
            append("**Fields:** $fieldCount")
            appendLine()
        }

        // Show if it's a script class
        if (classNode.isScript) {
            append("**Type:** Script class")
        }
    }.takeIf { it.isNotBlank() }

    /**
     * Build contextual information for fields.
     */
    private fun buildFieldContext(field: FieldNode): String? = buildString {
        // Show declaring class
        val declaringClass = field.declaringClass
        when {
            declaringClass?.isScript == true -> {
                append("**Type:** Script field")
                appendLine()
            }
            declaringClass != null -> {
                append("**Declared in:** ${declaringClass.nameWithoutPackage}")
                appendLine()
            }
        }

        // Show if it has an initializer
        if (field.initialExpression != null) {
            append("**Initialized:** Yes")
        }
    }.takeIf { it.isNotBlank() }

    /**
     * Build contextual information for properties.
     */
    private fun buildPropertyContext(property: PropertyNode): String? = buildString {
        // Show getter/setter information
        val getter = property.getterBlock
        val setter = property.setterBlock

        when {
            getter != null && setter != null -> append("**Access:** Read/Write property")
            getter != null -> append("**Access:** Read-only property")
            setter != null -> append("**Access:** Write-only property")
            else -> append("**Access:** Property")
        }
    }.takeIf { it.isNotBlank() }

    /**
     * Format a ConstantExpression for hover display.
     */
    private fun ConstantExpression.formatConstantExpression(): String = buildString {
        when (val value = this@formatConstantExpression.value) {
            is String -> append("String \"$value\"")
            is Number -> append("${value.javaClass.simpleName} $value")
            is Boolean -> append("Boolean $value")
            else -> append("Constant ${value?.javaClass?.simpleName ?: "null"}")
        }
    }

    /**
     * Format a MethodCallExpression for hover display.
     */
    private fun MethodCallExpression.formatMethodCallExpression(): String = buildString {
        append("Method: ")

        // Show the method name
        val methodText = method.text
        append(methodText)

        // Check for closure arguments to provide better information
        val args = arguments
        val hasClosureArg = when (args) {
            is ArgumentListExpression -> args.expressions.any { it is ClosureExpression }
            is ClosureExpression -> true
            else -> false
        }

        val argCount = when (args) {
            is ArgumentListExpression -> args.expressions.size
            else -> if (args != null) 1 else 0
        }

        append("(")
        if (argCount > 0) {
            if (hasClosureArg) {
                val closureCount = when (args) {
                    is ArgumentListExpression -> args.expressions.count { it is ClosureExpression }
                    is ClosureExpression -> 1
                    else -> 0
                }
                val regularArgCount = argCount - closureCount

                if (regularArgCount > 0) {
                    append("$regularArgCount argument${if (regularArgCount == 1) "" else "s"}")
                    if (closureCount > 0) append(", ")
                }
                if (closureCount > 0) {
                    append("$closureCount closure${if (closureCount == 1) "" else "s"}")
                }
            } else {
                append("$argCount argument${if (argCount == 1) "" else "s"}")
            }
        }
        append(")")
    }
}
