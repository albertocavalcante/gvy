package com.github.albertocavalcante.groovylsp.providers.callhierarchy

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.SymbolKind
import java.net.URI

class CallHierarchyProvider(private val compilationService: GroovyCompilationService) {

    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem> {
        val uri = URI.create(params.textDocument.uri)
        val position = params.position.toGroovyPosition()

        val visitor = compilationService.getAstModel(uri) ?: return emptyList()
        val symbolTable = compilationService.getSymbolTable(uri) ?: return emptyList()
        val node = visitor.getNodeAt(uri, position) ?: return emptyList()

        // Resolve definitions
        // If it's a call, we want the definition. If it's a def, we want itself.
        var definition = node.resolveToDefinition(visitor, symbolTable, strict = false) ?: node

        // HEURISTIC: Fallback for script method calls.
        // In top-level scripts, method calls often appear as VariableExpression (dynamic) or ConstantExpression
        // (the method name leaf) when the parser can't fully resolve the implicit 'this' or script context.
        // The core resolveToDefinition might fail to link these to the script's MethodNode.
        // TODO(#564): Improve parser to resolve strict methods better.
        //   See: https://github.com/albertocavalcante/gvy/issues/564
        //
        // This block attempts a name-based lookup in the same file if the standard resolution failed
        // (i.e. if definition == node, meaning it didn't resolve to something else).
        var callNode: MethodCallExpression? = null
        if (node is MethodCallExpression) {
            callNode = node
        } else if (node is ConstantExpression || node is VariableExpression) {
            val parent = visitor.getParent(node)
            if (parent is MethodCallExpression) {
                callNode = parent
            }
        }

        if (definition == node || (callNode != null && definition == node)) {
            if (callNode != null) {
                val methodName = callNode.methodAsString
                if (methodName != null) {
                    // Find first method in file with this name
                    val methods = visitor.getAllNodes().filterIsInstance<MethodNode>()
                    val fallback = methods.find { it.name == methodName }

                    if (fallback != null) {
                        definition = fallback
                    }
                }
            }
        }

        return when (definition) {
            is MethodNode -> listOf(createCallHierarchyItem(definition, uri))
            is ClassNode -> listOf(createCallHierarchyItem(definition, uri)) // Constructors treated as class
            else -> emptyList()
            // TODO: Handle fields/variables if Call Hierarchy supports them? Usually restricted to callables.
        }
    }

    private fun createCallHierarchyItem(node: ASTNode, uri: URI): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.uri = uri.toString()
        // Set proper range from AST node
        val nodeRange = node.toLspRange()
        item.range = nodeRange
        item.selectionRange = nodeRange

        when (node) {
            is MethodNode -> {
                item.name = node.name
                item.kind = SymbolKind.Method
                item.detail = node.parameters?.joinToString(", ") { it.type.nameWithoutPackage + " " + it.name }

                // Location logic would be strictly extracted here or use converters
            }

            is ClassNode -> {
                item.name = node.nameWithoutPackage
                item.kind = SymbolKind.Class
            }
        }
        return item
    }

    suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> {
        val uri = params.item.uri
        val position = params.item.selectionRange.start

        val referenceProvider = ReferenceProvider(compilationService)

        val references = referenceProvider.provideReferences(
            uri,
            position,
            includeDeclaration = false,
        )

        val calls = mutableMapOf<String, CallHierarchyIncomingCall>()

        references.collect { location ->
            val refUri = URI.create(location.uri)
            val refVisitor = compilationService.getAstModel(refUri) ?: return@collect
            val refRange = location.range
            val refPosition = refRange.start.toGroovyPosition()
            val refNode = refVisitor.getNodeAt(refUri, refPosition) ?: return@collect

            val enclosingNode = getEnclosingMethodOrClass(refNode, refVisitor)

            if (enclosingNode != null) {
                val key = "${location.uri}:${enclosingNode.lineNumber}:${enclosingNode.columnNumber}"

                val existing = calls[key]
                if (existing != null) {
                    existing.fromRanges.add(location.range)
                } else {
                    val fromItem = createCallHierarchyItem(enclosingNode, refUri)
                    calls[key] = CallHierarchyIncomingCall(fromItem, mutableListOf(location.range))
                }
            }
        }
        return calls.values.toList()
    }

    private fun getEnclosingMethodOrClass(node: ASTNode, visitor: GroovyAstModel): ASTNode? {
        var current: ASTNode? = node
        while (current != null) {
            val parent = visitor.getParent(current)
            if (parent is MethodNode || parent is ClassNode) { // Check ClassNode for fields/initializers
                return parent
            }
            current = parent
        }
        return null
    }

    fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall> {
        val uri = URI.create(params.item.uri)
        // We need to re-locate the method/symbol defined in 'item'
        // Since we don't persist node pointers, we use the position to find it again
        val position = params.item.selectionRange.start.toGroovyPosition()

        val visitor = compilationService.getAstModel(uri) ?: return emptyList()
        val symbolTable = compilationService.getSymbolTable(uri) ?: return emptyList()
        val node = visitor.getNodeAt(uri, position) ?: return emptyList()

        // Resolve to definition (should be MethodNode or ClassNode)
        val definition = node.resolveToDefinition(visitor, symbolTable, strict = false) ?: node

        if (definition is MethodNode) {
            val calls = mutableListOf<CallHierarchyOutgoingCall>()

            // Use visitor pattern for efficient traversal of only this method's code
            val callVisitor = object : CodeVisitorSupport() {
                override fun visitMethodCallExpression(call: MethodCallExpression) {
                    val callee = call.resolveToDefinition(visitor, symbolTable, strict = false)
                    if (callee != null) {
                        // TODO(#564): Cross-file resolution needs SourceNavigationService.
                        // Currently assumes same file for callee URI.
                        val calleeItem = createCallHierarchyItem(callee, uri)
                        val range = call.toLspRange()
                        calls.add(CallHierarchyOutgoingCall(calleeItem, listOf(range)))
                    }
                    super.visitMethodCallExpression(call)
                }
            }
            definition.code?.visit(callVisitor)
            return calls
        }

        return emptyList()
    }
}
