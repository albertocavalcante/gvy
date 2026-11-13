// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.callhierarchy

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolKind
import java.net.URI

class CallHierarchyProvider(private val compilationService: GroovyCompilationService) {

    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem> =
        prepareCallHierarchy(params) {}

    internal fun prepareCallHierarchy(
        params: CallHierarchyPrepareParams,
        ensureActive: () -> Unit,
    ): List<CallHierarchyItem> {
        ensureActive()
        val uri = URI.create(params.textDocument.uri)
        val source = captureSource(uri) ?: return emptyList()
        ensureActive()
        val node = source.index.codePointPosition(params.position)?.toGroovyPosition()
            ?.let { source.visitor.getNodeAt(uri, it) } ?: return emptyList()
        val definition = resolveDefinitionWithFallback(node, source.visitor, source.symbolTable)
        ensureActive()

        return when (definition) {
            is MethodNode, is ClassNode -> listOfNotNull(createCallHierarchyItem(definition, uri, source.index))
            else -> emptyList()
            // TODO: Handle fields/variables if Call Hierarchy supports them? Usually restricted to callables.
        }
    }

    private fun resolveDefinitionWithFallback(
        node: ASTNode,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable,
    ): ASTNode {
        // Resolve definitions
        // If it's a call, we want the definition. If it's a def, we want itself.
        val resolved = node.resolveToDefinition(astModel, symbolTable, strict = false) ?: node
        if (resolved !== node) {
            return resolved
        }

        // HEURISTIC: Fallback for script method calls.
        // In top-level scripts, method calls often appear as VariableExpression (dynamic) or ConstantExpression
        // (the method name leaf) when the parser can't fully resolve the implicit 'this' or script context.
        // The core resolveToDefinition might fail to link these to the script's MethodNode.
        // TODO(#564): Improve parser to resolve strict methods better.
        //   See: https://github.com/albertocavalcante/gvy/issues/564
        val callNode = extractEnclosingMethodCall(node, astModel) ?: return node
        return findScriptMethodFallback(callNode, astModel) ?: node
    }

    private fun extractEnclosingMethodCall(node: ASTNode, astModel: GroovyAstModel): MethodCallExpression? {
        if (node is MethodCallExpression) {
            return node
        }
        if (node is ConstantExpression || node is VariableExpression) {
            return astModel.getParent(node) as? MethodCallExpression
        }
        return null
    }

    private fun findScriptMethodFallback(call: MethodCallExpression, astModel: GroovyAstModel): MethodNode? {
        val methodName = call.methodAsString ?: return null
        return astModel.getAllNodes()
            .asSequence()
            .filterIsInstance<MethodNode>()
            .firstOrNull { it.name == methodName }
    }

    suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> {
        val uri = params.item.uri
        val position = params.item.selectionRange.start

        val referenceProvider = ReferenceProvider(compilationService, compilationService.getWorkspaceSymbolIndex())

        val references = referenceProvider.provideReferences(
            uri,
            position,
            includeDeclaration = false,
        )

        val calls = mutableMapOf<String, CallHierarchyIncomingCall>()
        val sources = mutableMapOf<URI, CallSource?>()

        references.collect { location ->
            val fromItem = caller(location, sources) ?: return@collect
            val key = "${fromItem.uri}:${fromItem.range.start.line}:${fromItem.range.start.character}"
            val existing = calls[key]
            if (existing != null) {
                existing.fromRanges.add(location.range)
            } else {
                calls[key] = CallHierarchyIncomingCall(fromItem, mutableListOf(location.range))
            }
        }
        return calls.values.toList()
    }

    private data class CallSource(val visitor: GroovyAstModel, val symbolTable: SymbolTable, val index: TextIndex)

    private fun captureSource(uri: URI): CallSource? = compilationService.getCompiledSnapshot(uri)?.let {
        CallSource(it.parseResult.astModel, it.parseResult.symbolTable, TextIndex(it.sourceText))
    }

    private fun caller(location: Location, sources: MutableMap<URI, CallSource?>): CallHierarchyItem? {
        val uri = URI.create(location.uri)
        val source = sources.getOrPut(uri) { captureSource(uri) } ?: return null
        val position = source.index.codePointPosition(location.range.start)?.toGroovyPosition() ?: return null
        return source.visitor.getNodeAt(uri, position)
            ?.let { getEnclosingMethodOrClass(it, source.visitor) }
            ?.let { createCallHierarchyItem(it, uri, source.index) }
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

    fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall> =
        outgoingCalls(params) {}

    internal fun outgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
        ensureActive: () -> Unit,
    ): List<CallHierarchyOutgoingCall> {
        ensureActive()
        val uri = URI.create(params.item.uri)
        val source = captureSource(uri) ?: return emptyList()
        ensureActive()
        val node = source.index.codePointPosition(params.item.selectionRange.start)?.toGroovyPosition()
            ?.let { source.visitor.getNodeAt(uri, it) } ?: return emptyList()

        // Resolve to definition (should be MethodNode or ClassNode)
        val definition = node.resolveToDefinition(source.visitor, source.symbolTable, strict = false) ?: node
        val callsMap = mutableMapOf<String, CallHierarchyOutgoingCall>()
        if (definition is MethodNode) {
            val callVisitor = CallVisitor(source, uri, callsMap, ensureActive)
            definition.code?.visit(callVisitor)
        }
        ensureActive()
        return callsMap.values.toList()
    }

    private class CallVisitor(
        private val source: CallSource,
        private val uri: URI,
        private val callsMap: MutableMap<String, CallHierarchyOutgoingCall>,
        private val ensureActive: () -> Unit,
    ) : CodeVisitorSupport() {

        private fun addCall(call: Expression) {
            ensureActive()
            val callee = call.resolveToDefinition(source.visitor, source.symbolTable, strict = false)
            val range = call.toLspRange()?.let(source.index::utf16Range)
            if (callee != null && range != null) {
                // Use a stable key for the callee.
                // TODO(#564): Include URI once cross-file resolution is supported.
                val calleeKey = "${callee.lineNumber}:${callee.columnNumber}"

                val existing = callsMap[calleeKey]
                if (existing != null) {
                    existing.fromRanges.add(range)
                } else {
                    val calleeItem = createCallHierarchyItem(callee, uri, source.index) ?: return
                    callsMap[calleeKey] = CallHierarchyOutgoingCall(calleeItem, mutableListOf(range))
                }
            }
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            addCall(call)
            super.visitMethodCallExpression(call)
        }

        override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
            addCall(call)
            super.visitStaticMethodCallExpression(call)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            addCall(call)
            super.visitConstructorCallExpression(call)
        }
    }

    companion object {
        private fun createCallHierarchyItem(node: ASTNode, uri: URI, index: TextIndex): CallHierarchyItem? {
            val raw = node.toLspRange() ?: return null
            val nodeRange = index.utf16Range(raw) ?: return null
            val item = CallHierarchyItem()
            item.uri = uri.toString()
            // Set proper range from AST node
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
    }
}
