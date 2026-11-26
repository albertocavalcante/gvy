// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.indexing.toMemberInfo
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.Symbol
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.native.ReceiverBoundMemberLookup
import com.github.albertocavalcante.gvy.semantics.toIndexClassName
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Local type inference needs no index. Cross-file reads use one frozen graph and qualify each consulted owner. */
internal class HoverMemberLookup(
    capture: () -> CapturedWorkspaceReferences,
    private val qualify: (URI, SemanticDocumentSnapshot) -> (() -> Boolean)?,
    localTypes: () -> Set<String> = { emptySet() },
) : HoverMemberQueries(), ReceiverBoundMemberLookup {
    companion object {
        fun forRequest(
            compilation: GroovyCompilationService,
            uri: URI,
            held: CompiledDocumentSnapshot,
        ): HoverMemberLookup {
            val readiness = lazy { compilation.referenceReadiness(uri) }
            return HoverMemberLookup(
                capture = { readiness.value.captureMembersView() },
                qualify = { owner, document -> readiness.value.qualifyMember(owner, document, held) },
                localTypes = {
                    held.parseResult.ast?.classes.orEmpty().mapTo(mutableSetOf()) { it.name.toIndexClassName() }
                },
            )
        }
    }

    private val localNames = lazy(localTypes)
    private val view = lazy(capture)
    private val checks = mutableMapOf<URI, () -> Boolean>()
    private var unavailable: ResponseErrorException? = null

    override fun forReceiver(declaration: ClassNode?): MemberLookup? {
        if (declaration == null || !declaration.isPrimaryClassNode) return null
        val source = declaration.module?.context?.inputDocumentUri() ?: return null
        val owner = declaration.name.toIndexClassName()
        return object : HoverMemberQueries() {
            override fun members(classFqn: String): List<SymbolInfo> =
                if (classFqn == owner) this@HoverMemberLookup.members(classFqn, source) else emptyList()
        }
    }

    // An unbound call has no foreign declaration it can consult.
    override fun members(classFqn: String): List<SymbolInfo> = members(classFqn, null)

    private fun members(classFqn: String, boundSource: URI?): List<SymbolInfo> {
        // NativeTypeContext already tried this held module. A local miss is not a cross-file index lookup,
        // especially when a current recovery AST has no complete index publication.
        if (classFqn in localNames.value) return emptyList()
        val id = Symbol.ofInternalName(classFqn).id
        val references = view.value
        try {
            val uri = references.uniqueDefinition(id)?.first ?: return emptyList()
            if (uri != boundSource) return emptyList()
            val stored = checkNotNull(references.getDocumentSnapshot(uri))
            checks.getOrPut(uri) {
                qualify(uri, stored) ?: throw ResponseErrorException(
                    ResponseError(ResponseErrorCode.RequestFailed, "Cannot qualify workspace members of $classFqn", null),
                )
            }
            return stored.document.symbols.filter { it.owner == id }
        } catch (failure: ResponseErrorException) {
            // Type inference can translate a failed lookup to an unknown type. Preserve the refusal for delivery.
            unavailable = failure
            throw failure
        }
    }

    fun isDatabaseCurrent(): Boolean = !view.isInitialized() || view.value.isDatabaseCurrent()

    fun isCurrent(): Boolean {
        unavailable?.let { throw it }
        return !view.isInitialized() || (checks.values.all { it() } && view.value.isCurrent())
    }

}

internal abstract class HoverMemberQueries : MemberLookup {
    protected abstract fun members(classFqn: String): List<SymbolInfo>

    override fun findField(classFqn: String, fieldName: String): MemberInfo? = members(classFqn)
        .firstOrNull { it.kind == SymbolKind.FIELD && it.name == fieldName }?.toMemberInfo()

    override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
        findMethods(classFqn, methodName, arity).firstOrNull()

    override fun findMethods(classFqn: String, methodName: String, arity: Int?): List<MemberInfo> = members(classFqn)
        .filter { it.kind == SymbolKind.METHOD && it.name == methodName }
        .filter { arity == null || Symbol(it.symbol).arity == arity }
        .map { it.toMemberInfo() }

    override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = members(classFqn)
        .filter { it.kind == SymbolKind.FIELD || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.PROPERTY }
        .map { it.toMemberInfo() }
}
