// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.QualifiedReferences
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.providers.crossfile.navigationChanged
import com.github.albertocavalcante.gvy.gls.providers.crossfile.navigationUnavailable
import com.github.albertocavalcante.gvy.gls.providers.crossfile.requireNavigationSource
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import kotlinx.coroutines.currentCoroutineContext
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import java.net.URI

/** Method IDs alone cannot distinguish a source method from an identically named SDK declaration. */
internal class ReferenceBindings(
    private val compilation: GroovyCompilationService,
    private val graph: QualifiedReferences,
    private val origin: URI,
    private val originParse: CompiledDocumentSnapshot,
) {
    private val peers = mutableMapOf<URI, SnapshotAcquisition.Ready>()

    suspend fun accepts(symbol: String, name: String, location: Location): Boolean {
        val (declaringUri, definition) = graph.index.uniqueDefinition(symbol) ?: return true
        if (definition.kind != SymbolKind.METHOD) return true
        val caller = URI.create(location.uri)
        if (caller == declaringUri) return true
        return methodUseMatches(caller, location.range.start, declaringUri, name)
    }

    suspend fun requireSelection(symbol: String, name: String, position: Position) {
        val (declaringUri, info) = graph.index.uniqueDefinition(symbol) ?: return
        if (info.kind == SymbolKind.METHOD && !methodUseMatches(origin, position, declaringUri, name)) {
            throw navigationUnavailable("The selected call and indexed declaration have different owners")
        }
    }

    private suspend fun methodUseMatches(caller: URI, position: Position, declaringUri: URI, name: String): Boolean {
        val held = capture(caller)
        val at = TextIndex(held.sourceText).codePointPosition(position)
            ?: throw navigationUnavailable("Missing method occurrence position in $caller")
        val model = held.parseResult.astModel
        val nodes = model.getNodes(caller)
        val call = nodes.filterIsInstance<MethodCallExpression>().singleOrNull {
            it.methodAsString == name && it.method.lineNumber == at.line + 1 &&
                at.character + 1 in it.method.columnNumber until it.method.lastColumnNumber
        }
        val direct =
            call?.methodTarget ?: call?.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        val owner = if (direct != null) {
            direct.declaringClass
        } else if (call != null) {
            val receiver = call.objectExpression
            val type = if (call.isImplicitThis || receiver is VariableExpression && receiver.isThisExpression) {
                generateSequence(model.getParent(call)) { model.getParent(it) }
                    .filterIsInstance<MethodNode>().firstOrNull()?.declaringClass
            } else {
                (receiver as? VariableExpression)?.accessedVariable?.type ?: receiver.type
            }
            type?.redirect()?.getMethods(name)?.map { it.declaringClass.redirect() }?.distinct()?.singleOrNull()
        } else {
            nodes.filterIsInstance<StaticMethodCallExpression>().singleOrNull {
                it.method == name && it.lineNumber == at.line + 1 && it.columnNumber <= at.character + 1 &&
                    it.lastColumnNumber > at.character + 1
            }?.ownerType?.redirect()?.getMethods(name)?.map { it.declaringClass.redirect() }?.distinct()?.singleOrNull()
        }
        return matches(owner, declaringUri)
    }

    private fun matches(owner: ClassNode?, uri: URI): Boolean {
        if (owner == null) throw navigationUnavailable("Cannot qualify method binding for $uri")
        if (owner.isResolved && !owner.isPrimaryClassNode) return false
        val sourceUri = owner.module?.context?.inputDocumentUri()
            ?: throw navigationUnavailable("No source authority for method on ${owner.name}")
        return sourceUri == uri
    }

    private suspend fun capture(uri: URI): CompiledDocumentSnapshot {
        if (uri == origin) return originParse
        peers[uri]?.let { return it.snapshot }
        val available = compilation.acquireSnapshot(uri, mode = graph.modeFor(uri)).requireNavigationSource(uri)
        val ready = available as? SnapshotAcquisition.Ready
            ?: throw navigationUnavailable("Incomplete reference source at $uri")
        if (!graph.matches(uri, ready.snapshot) || ready.snapshot.referenceProducer?.complete != true) {
            throw navigationUnavailable("Unqualified reference source at $uri")
        }
        peers[uri] = ready
        return ready.snapshot
    }

    suspend fun validate() {
        val context = currentCoroutineContext()
        if (peers.values.any {
                !context.inputsCurrent(it.isCurrent)
            }
        ) {
            throw navigationChanged("Reference source changed")
        }
    }
}
