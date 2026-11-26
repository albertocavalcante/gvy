// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.callhierarchy

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.hasCyclicSuperClassChain
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverMethodSelection
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.failingAsInternalError
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import kotlinx.coroutines.flow.toList
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.lang.reflect.Modifier
import java.net.URI

class CallHierarchyProvider(private val compilationService: GroovyCompilationService) {

    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem> {
        val uri = URI.create(params.textDocument.uri)
        val source = captureSource(uri) ?: return emptyList()
        return prepareCallHierarchy(uri, params.position, source)
    }

    internal fun prepareCallHierarchy(
        uri: URI,
        position: Position,
        snapshot: CompiledDocumentSnapshot,
    ): List<CallHierarchyItem> = prepareCallHierarchy(
        uri,
        position,
        CallSource(snapshot),
    )

    private fun prepareCallHierarchy(uri: URI, position: Position, source: CallSource): List<CallHierarchyItem> {
        val node = source.index.codePointPosition(position)?.toGroovyPosition()
            ?.let { source.visitor.getNodeAt(uri, it) } ?: return emptyList()
        val definition = resolveDeclaration(node, source.visitor, source.symbolTable)

        val declaration = source.localDeclaration(definition, uri) ?: return emptyList()
        return listOfNotNull(createCallHierarchyItem(declaration, uri, source.index))
    }

    private fun resolveDeclaration(
        node: ASTNode,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable,
    ): ASTNode {
        val parent = astModel.getParent(node)
        val call = when {
            node is MethodCallExpression -> node
            parent is MethodCallExpression && isMethodToken(parent, node) -> parent
            else -> null
        }
        return if (call != null) {
            methodTarget(call, astModel) ?: node
        } else {
            node.resolveToDefinition(astModel, symbolTable, strict = false) ?: node
        }
    }

    private fun methodTarget(call: MethodCallExpression, model: GroovyAstModel): MethodNode? =
        directMethodTarget(call) ?: selectCallMethod(call, model, getEnclosingMethodOrClass(call, model))

    suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> =
        failingAsInternalError("finding incoming calls") { collectIncomingCalls(params) }

    internal suspend fun incomingCalls(
        uri: URI,
        position: Position,
        origin: CompiledDocumentSnapshot,
        references: CapturedWorkspaceReferences,
        callerSnapshot: suspend (URI) -> CompiledDocumentSnapshot,
    ): List<CallHierarchyIncomingCall> {
        val locations = ReferenceProvider(
            compilationService,
        ).provideCapturedReferences(uri, position, origin, references)
            .toList()
        val originSource = CallSource(origin)
        val selected = originSource.nodeAt(uri, position)?.let {
            val definition = resolveDeclaration(it, originSource.visitor, originSource.symbolTable)
            originSource.localDeclaration(definition, uri)
        } ?: IncomingCallTarget.unavailable()
        val target = IncomingCallTarget(uri, selected, references.visibleDocuments)
        val sources = locations.map { URI.create(it.uri) }.distinct().associateWith { callerUri ->
            val snapshot = callerSnapshot(callerUri)
            val indexed = references.getDocumentSnapshot(callerUri) ?: IncomingCallTarget.unavailable()
            CallSource(snapshot) to target.methodSites(indexed.document)
        }
        val calls = mutableMapOf<String, CallHierarchyIncomingCall>()
        for (location in locations) {
            val callerUri = URI.create(location.uri)
            val (source, sites) = sources.getValue(callerUri)
            val site = source.nodeAt(callerUri, location.range.start) ?: IncomingCallTarget.unavailable()
            if (target.isNonCallTypeUse(site, source.visitor)) continue
            val enclosing = getEnclosingMethodOrClass(site, source.visitor) ?: IncomingCallTarget.unavailable()
            target.requireBinding(site, enclosing, source.visitor, sites)
            val fromItem =
                createCallHierarchyItem(enclosing, callerUri, source.index) ?: IncomingCallTarget.unavailable()
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

    private suspend fun collectIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): List<CallHierarchyIncomingCall> {
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

    private data class CallSource(val visitor: GroovyAstModel, val symbolTable: SymbolTable, val index: TextIndex) {
        constructor(snapshot: CompiledDocumentSnapshot) :
            this(snapshot.parseResult.astModel, snapshot.parseResult.symbolTable, TextIndex(snapshot.sourceText))

        private val classes by lazy { visitor.getAllClassNodes() }

        fun nodeAt(uri: URI, position: Position): ASTNode? =
            index.codePointPosition(position)?.toGroovyPosition()?.let { visitor.getNodeAt(uri, it) }

        fun localDeclaration(node: ASTNode, uri: URI): ASTNode? = when (node) {
            is ClassNode -> classes.firstOrNull { declaration ->
                (declaration === node || declaration === node.redirect()) && visitor.getUri(declaration) == uri
            }
            is MethodNode -> node.takeIf { visitor.getUri(it) == uri }
            else -> null
        }
    }

    private fun captureSource(uri: URI): CallSource? = compilationService.getCompiledSnapshot(uri)?.let {
        CallSource(it)
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

    fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall> {
        val uri = URI.create(params.item.uri)
        val source = captureSource(uri) ?: return emptyList()
        return outgoingCalls(uri, params.item.selectionRange.start, source)
    }

    internal fun outgoingCalls(
        uri: URI,
        position: Position,
        snapshot: CompiledDocumentSnapshot,
    ): List<CallHierarchyOutgoingCall> = outgoingCalls(
        uri,
        position,
        CallSource(snapshot),
    )

    private fun outgoingCalls(uri: URI, position: Position, source: CallSource): List<CallHierarchyOutgoingCall> {
        val node = source.index.codePointPosition(position)?.toGroovyPosition()
            ?.let { source.visitor.getNodeAt(uri, it) } ?: return emptyList()

        // Resolve to definition (should be MethodNode or ClassNode)
        val definition = resolveDeclaration(node, source.visitor, source.symbolTable)
        val callsMap = mutableMapOf<String, CallHierarchyOutgoingCall>()
        if (definition is MethodNode) {
            val callVisitor = CallVisitor(source, uri, callsMap)
            definition.code?.visit(callVisitor)
        }
        return callsMap.values.toList()
    }

    private inner class CallVisitor(
        private val source: CallSource,
        private val uri: URI,
        private val callsMap: MutableMap<String, CallHierarchyOutgoingCall>,
    ) : CodeVisitorSupport() {

        private fun addCall(call: Expression) {
            val resolved = if (call is MethodCallExpression) {
                methodTarget(call, source.visitor)
            } else {
                call.resolveToDefinition(source.visitor, source.symbolTable, strict = false)
            }
            val range = call.toLspRange()?.let(source.index::utf16Range)
            if (resolved != null && range != null) {
                val callee = source.localDeclaration(resolved, uri)
                if (callee == null) {
                    throw ResponseErrorException(
                        ResponseError(
                            ResponseErrorCode.RequestFailed,
                            "Call target is not a captured local declaration",
                            null,
                        ),
                    )
                }
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
        private fun declaredName(node: ASTNode): String? = when (node) {
            is MethodNode -> if (node.name == "<init>") node.declaringClass?.nameWithoutPackage else node.name
            is ClassNode -> node.nameWithoutPackage.substringAfterLast('$')
            else -> null
        }

        private fun createCallHierarchyItem(node: ASTNode, uri: URI, index: TextIndex): CallHierarchyItem? {
            val raw = node.toLspRange() ?: return null
            val nodeRange = index.utf16Range(raw) ?: return null
            val item = CallHierarchyItem()
            item.uri = uri.toString()
            // Set proper range from AST node
            item.range = nodeRange
            // LSP: selectionRange is the name. Resolving from the declaration's first token would hit
            // its return type (`Order place()`), not the method.
            item.selectionRange = declaredName(node)?.let { firstNameOccurrence(index, nodeRange, it) } ?: nodeRange

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

internal fun directMethodTarget(call: MethodCallExpression): MethodNode? =
    call.methodTarget ?: call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)

private fun receiverClass(call: MethodCallExpression, lexicalOwner: ClassNode?): ClassNode? {
    val receiver = call.objectExpression
    return when {
        call.isImplicitThis -> lexicalOwner
        receiver is VariableExpression && receiver.isThisExpression -> lexicalOwner
        receiver is VariableExpression && receiver.isSuperExpression -> lexicalOwner?.superClass
        receiver is VariableExpression -> receiver.accessedVariable?.type?.takeUnless {
            ClassHelper.isDynamicTyped(it) || it == ClassHelper.OBJECT_TYPE
        }
        receiver is ClassExpression -> receiver.type
        receiver is ConstantExpression -> receiver.type.takeIf { receiver.value != null }
        receiver is ConstructorCallExpression -> receiver.type.takeUnless { receiver.isUsingAnonymousInnerClass }
        else -> null
    }?.redirect()
}

/** Keep the original declaration selected by Groovy, including when it omits default arguments. */
internal fun selectCallMethod(
    call: MethodCallExpression,
    model: GroovyAstModel,
    enclosing: ASTNode?,
): MethodNode? {
    val ancestors = generateSequence<ASTNode>(call, model::getParent).takeWhile { it !== enclosing }
    val delegated = call.isImplicitThis && ancestors.any { it is ClosureExpression }
    val lexicalOwner = when (enclosing) {
        is MethodNode -> enclosing.declaringClass
        is ClassNode -> enclosing
        // The top-level statement block can be tracked directly under the module.
        else -> model.getAllClassNodes().singleOrNull { it.isScript }
    }
    val receiver = receiverClass(call, lexicalOwner)
    return if (delegated || call.isSpreadSafe || receiver == null) {
        null
    } else {
        selectReceiverMethod(call, receiver)
    }
}

private fun selectReceiverMethod(call: MethodCallExpression, receiver: ClassNode): MethodNode? {
    val name = call.methodAsString ?: return null
    val arguments = (call.arguments as? TupleExpression)?.expressions ?: return null
    val candidates = if (receiver.hasCyclicSuperClassChain()) emptyList() else receiver.getMethods(name)
    val methods = candidates.filter { call.objectExpression !is ClassExpression || it.isStatic }
    // Groovy dispatches on runtime types. A variable declared as a base type can select a narrower overload.
    val unknownOverload = methods.size > 1 && arguments.any { !hasExactArgumentType(it) }
    return if (unknownOverload) null else HoverMethodSelection.select(receiver, methods, arguments).singleOrNull()
}

private fun hasExactArgumentType(argument: Expression): Boolean = when (argument) {
    is ConstantExpression -> argument.value != null
    is ConstructorCallExpression -> !argument.isUsingAnonymousInnerClass
    else -> ClassHelper.isPrimitiveType(argument.type) || Modifier.isFinal(argument.type.modifiers)
}
