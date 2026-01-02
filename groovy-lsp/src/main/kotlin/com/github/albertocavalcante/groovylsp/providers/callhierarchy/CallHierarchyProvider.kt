package com.github.albertocavalcante.groovylsp.providers.callhierarchy

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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
        } else if (node.javaClass.simpleName == "ConstantExpression" || node is VariableExpression) {
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
        // Default range
        item.range = org.eclipse.lsp4j.Range(Position(0, 0), Position(0, 0))
        item.selectionRange = org.eclipse.lsp4j.Range(Position(0, 0), Position(0, 0))

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

    fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> {
        val uri = params.item.uri
        val position = params.item.selectionRange.start

        val referenceProvider = ReferenceProvider(compilationService)

        return runBlocking {
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
            calls.values.toList()
        }
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

        val calls = mutableListOf<CallHierarchyOutgoingCall>()

        if (definition is MethodNode) {
            definition.code?.let { block ->
                val allNodes = visitor.getAllNodes()
                // Naive approach: Find all MethodCallExpressions whose source location is INSIDE the definition range
                // Better approach: Traverse the block directly. But getAllNodes is flat.
                // We'll iterate getAllNodes and check for containment.
                val defLine = definition.lineNumber
                val defEndLine = definition.lastLineNumber

                allNodes.forEach { candidate ->
                    if (candidate is MethodCallExpression && isInside(candidate, definition)) {
                        // Resolve what this call points to
                        val callee = candidate.resolveToDefinition(visitor, symbolTable, strict = false)
                        if (callee != null) {
                            // Find where callee is defined to get its URI and range
                            // This is tricky because resolveToDefinition returns ASTNode, but we need URI
                            // If callee is in same file, use uri.
                            // If in other file, we need to know that.
                            // GroovySymbolResolver might attach source info?
                            // Or we check if it has valid line/col.
                            // For simplicity, we assume same file or use available info.
                            // REALITY CHECK: resolving cross-file definitions needs SourceNavigationService
                            // or we just providing basic info. Call Hierarchy requires item.uri.
                            // If we can't find URI, we skip.

                            // NOTE: For now, supporting same-file or where node has location.
                            // In advanced LSP, we use a global index.

                            val calleeItem =
                                createCallHierarchyItem(callee, uri) // Assumes same file for now! FIX LATER

                            // Refine URI if we can determine it from the node (e.g. if it's from another ClassNode in another file)
                            // But ASTNode usually doesn't store URI.

                            val range = candidate.toLspRange()
                            calls.add(CallHierarchyOutgoingCall(calleeItem, listOf(range)))
                        }
                    }
                }
            }
        }

        return calls
    }

    private fun isInside(inner: ASTNode, outer: ASTNode): Boolean {
        // Simple line check
        return inner.lineNumber >= outer.lineNumber && inner.lastLineNumber <= outer.lastLineNumber
    }
}
