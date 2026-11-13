// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceDefinitionParams
import com.github.albertocavalcante.gvy.gls.sources.SourceEditTarget
import com.github.albertocavalcante.gvy.gls.sources.SourceEditTargetKind
import com.github.albertocavalcante.gvy.gls.sources.sourceEditTargets
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.ColorInformation
import org.eclipse.lsp4j.ColorPresentation
import org.eclipse.lsp4j.ColorPresentationParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentColorParams
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.DocumentLinkParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.InlineValue
import org.eclipse.lsp4j.InlineValueParams
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Moniker
import org.eclipse.lsp4j.MonikerParams
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WillSaveTextDocumentParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.util.concurrent.CompletableFuture

/** Managed dependency views never enter workspace compilation, analysis, indexes or editing providers. */
// Every URI-bearing LSP method is guarded explicitly and covered by an interface-wide test.
@Suppress("TooManyFunctions")
class ManagedSourceDocumentService(
    private val delegate: TextDocumentService,
    private val registry: ExternalSourceRegistry,
    private val sourceDefinitions: (SourceDefinitionParams) -> CompletableFuture<List<LocationLink>>,
    private val definitionLinkSupport: () -> Boolean = { false },
) : TextDocumentService by delegate {
    private val documents = ManagedDocumentLifecycles()

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseUri(params.textDocument.uri) ?: return
        withDocument(uri) { lifecycle ->
            val ticket = Any()
            val reentrant = Thread.holdsLock(lifecycle.monitor)
            synchronized(lifecycle.monitor) {
                lifecycle.admission.set(ManagedDocumentAdmission(ticket, params.textDocument.version))
                lifecycle.deferredOpen = if (reentrant) DeferredManagedDocumentOpen(ticket, params) else null
            }
            if (!reentrant) completeAdmission(uri, lifecycle, ticket, params)
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseUri(params.textDocument.uri) ?: return
        withDocument(uri) { lifecycle ->
            bufferNotification(uri, lifecycle, { bufferChange(it, params) }) { delegate.didChange(params) }
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseUri(params.textDocument.uri) ?: return
        withDocument(uri) { lifecycle ->
            synchronized(lifecycle.monitor) {
                val admission = lifecycle.admission.getAndSet(null)
                lifecycle.deferredOpen = null
                registry.closed(uri)
                if (
                    admission?.delegated == true ||
                    (admission == null && !knownManaged(params.textDocument.uri))
                ) {
                    delegate.didClose(params)
                }
            }
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = parseUri(params.textDocument.uri) ?: return
        withDocument(uri) { lifecycle ->
            bufferNotification(uri, lifecycle, { it.copy(save = params) }) { delegate.didSave(params) }
        }
    }

    override fun willSave(params: WillSaveTextDocumentParams) {
        val uri = parseUri(params.textDocument.uri) ?: return
        withDocument(uri) { lifecycle ->
            bufferNotification(uri, lifecycle, { it.copy(willSave = params) }) { delegate.willSave(params) }
        }
    }

    private fun bufferNotification(
        uri: URI,
        lifecycle: ManagedDocumentLifecycle,
        update: (ManagedDocumentAdmission) -> ManagedDocumentAdmission,
        untracked: () -> Unit,
    ) {
        val hasNoOpenDocument = synchronized(lifecycle.monitor) {
            val admission = lifecycle.admission.get()
            when {
                admission == null -> true
                admission.resolved && admission.guarded -> {
                    registry.changed(uri)
                    false
                }
                else -> {
                    lifecycle.admission.set(update(admission))
                    false
                }
            }
        }
        if (
            hasNoOpenDocument &&
            !Thread.holdsLock(lifecycle.monitor) &&
            !registry.shouldGuardDocument(uri)
        ) {
            synchronized(lifecycle.monitor) {
                if (lifecycle.admission.get() == null) untracked()
            }
        }
    }

    private fun <T> withDocument(uri: URI, action: (ManagedDocumentLifecycle) -> T): T =
        documents.withEntry(uri) { lifecycle ->
            val result = action(lifecycle)
            if (!Thread.holdsLock(lifecycle.monitor)) drainDeferred(uri, lifecycle)
            result
        }

    private fun drainDeferred(uri: URI, lifecycle: ManagedDocumentLifecycle) {
        while (true) {
            val acquired = synchronized(lifecycle.monitor) {
                if (lifecycle.draining) {
                    false
                } else {
                    lifecycle.draining = true
                    true
                }
            }
            if (!acquired) return
            try {
                drainNotifications(uri, lifecycle)
            } finally {
                synchronized(lifecycle.monitor) { lifecycle.draining = false }
            }
            val hasPendingNotifications = synchronized(lifecycle.monitor) {
                lifecycle.deferredOpen != null ||
                    lifecycle.admission.get()?.let { admission ->
                        admission.resolved && admission.delegated && admission.hasNotifications()
                    } == true
            }
            if (!hasPendingNotifications) return
        }
    }

    private fun drainNotifications(uri: URI, lifecycle: ManagedDocumentLifecycle) {
        while (true) {
            val deferred = synchronized(lifecycle.monitor) {
                lifecycle.deferredOpen.also { lifecycle.deferredOpen = null }
            }
            if (deferred != null) {
                completeAdmission(uri, lifecycle, deferred.ticket, deferred.params)
                continue
            }
            val buffered = synchronized(lifecycle.monitor) {
                val admission = lifecycle.admission.get() ?: return@synchronized null
                if (!admission.resolved || !admission.delegated || !admission.hasNotifications()) {
                    return@synchronized null
                }
                lifecycle.admission.set(admission.withoutNotifications())
                admission
            } ?: return
            flushBuffered(uri, lifecycle, buffered)
        }
    }

    private fun completeAdmission(
        uri: URI,
        lifecycle: ManagedDocumentLifecycle,
        ticket: Any,
        params: DidOpenTextDocumentParams,
    ) {
        if (lifecycle.admission.get()?.ticket !== ticket) return
        check(!Thread.holdsLock(lifecycle.monitor)) {
            "Filesystem admission must run outside the lifecycle monitor"
        }
        val guarded = sourceScheme(params.textDocument.uri) || registry.shouldGuardDocument(uri)
        synchronized(lifecycle.monitor) {
            val admission = lifecycle.admission.get()?.takeIf { it.ticket === ticket } ?: return
            registry.opened(uri, params.textDocument.text)
            val resolved = admission.copy(guarded = guarded, resolved = true, delegated = !guarded)
            lifecycle.admission.set(if (guarded) resolved.withoutNotifications() else resolved)
            if (guarded) {
                if (admission.change != null || admission.rejectedChange != null) registry.changed(uri)
            } else {
                // Publish before calling the delegate: synchronous callbacks can close or reopen this URI.
                delegate.didOpen(params)
            }
        }
    }

    private fun flushBuffered(uri: URI, lifecycle: ManagedDocumentLifecycle, admission: ManagedDocumentAdmission) {
        check(!Thread.holdsLock(lifecycle.monitor)) {
            "Filesystem admission must run outside the lifecycle monitor"
        }
        val guarded = registry.shouldGuardDocument(uri)
        synchronized(lifecycle.monitor) {
            fun canDelegateNotification(): Boolean = lifecycle.admission.get()?.let { current ->
                current.ticket === admission.ticket && current.delegated
            } == true
            if (!canDelegateNotification()) return
            if (guarded) {
                // Retire native facts before any newly managed bytes reach DocumentProvider or compilation.
                val current = requireNotNull(lifecycle.admission.get())
                lifecycle.admission.set(current.copy(guarded = true, delegated = false).withoutNotifications())
                registry.changed(uri)
                delegate.didClose(DidCloseTextDocumentParams(org.eclipse.lsp4j.TextDocumentIdentifier(uri.toString())))
                return
            }
            admission.change?.takeIf { canDelegateNotification() }?.let { change ->
                delegate.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri.toString(), change.version),
                        change.text?.let { text -> listOf(TextDocumentContentChangeEvent(text)) }.orEmpty(),
                    ),
                )
            }
            admission.rejectedChange?.takeIf { canDelegateNotification() }?.let(delegate::didChange)
            admission.willSave?.takeIf { canDelegateNotification() }?.let(delegate::willSave)
            admission.save?.takeIf { canDelegateNotification() }?.let(delegate::didSave)
        }
    }

    private fun ManagedDocumentAdmission.hasNotifications(): Boolean =
        change != null || rejectedChange != null || willSave != null || save != null

    private fun ManagedDocumentAdmission.withoutNotifications(): ManagedDocumentAdmission = copy(
        openingVersion = change?.version ?: openingVersion,
        change = null,
        rejectedChange = null,
        willSave = null,
        save = null,
    )

    private fun bufferChange(
        admission: ManagedDocumentAdmission,
        params: DidChangeTextDocumentParams,
    ): ManagedDocumentAdmission {
        // Full synchronization allows bounded latest-state buffering; unsupported ranged events remain rejected by GTD.
        if (params.contentChanges.any { it.range != null }) return admission.copy(rejectedChange = params)
        val version = params.textDocument.version ?: return admission
        if (version <= (admission.change?.version ?: admission.openingVersion)) return admission
        val text = params.contentChanges.lastOrNull()?.text ?: admission.change?.text
        return admission.copy(change = BufferedDocumentChange(version, text))
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        if (!managed(params.textDocument.uri)) return delegate.definition(params)
        val pending = sourceDefinitions(SourceDefinitionParams(params.textDocument.uri, params.position))
        val response: CompletableFuture<Either<List<Location>, List<LocationLink>>> = pending.thenApply { links ->
            if (definitionLinkSupport()) {
                Either.forRight<List<Location>, List<LocationLink>>(links)
            } else {
                Either.forLeft<List<Location>, List<LocationLink>>(
                    links.map { Location(it.targetUri, it.targetSelectionRange) },
                )
            }
        }
        response.whenComplete { _, _ -> if (response.isCancelled) pending.cancel(true) }
        return response
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        guardEdits(params.textDocument.uri, Either.forLeft(emptyList())) { delegate.completion(params) }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> = if (managed(params.textDocument.uri)) {
        CompletableFuture.completedFuture(null)
    } else {
        delegate.hover(params)
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(null)
        } else {
            delegate.signatureHelp(params)
        }

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        } else {
            delegate.declaration(params)
        }

    override fun typeDefinition(
        params: TypeDefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = if (managed(params.textDocument.uri)) {
        CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    } else {
        delegate.typeDefinition(params)
    }

    override fun implementation(
        params: ImplementationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = if (managed(params.textDocument.uri)) {
        CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    } else {
        delegate.implementation(params)
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.references(params)
        }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.documentHighlight(params)
        }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = if (managed(params.textDocument.uri)) {
        CompletableFuture.completedFuture(emptyList())
    } else {
        delegate.documentSymbol(params)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        guardEdits(params.textDocument.uri, emptyList(), { editTargetsAllowed(sourceEditTargets(it)) }) {
            delegate.codeAction(params)
        }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.codeLens(params) }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.formatting(params) }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.rangeFormatting(params) }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.onTypeFormatting(params) }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
        guardEdits(params.textDocument.uri, null, { it == null || editTargetsAllowed(sourceEditTargets(it)) }) {
            delegate.rename(params)
        }

    override fun linkedEditingRange(params: LinkedEditingRangeParams): CompletableFuture<LinkedEditingRanges?> =
        guardEdits(params.textDocument.uri, null) { delegate.linkedEditingRange(params) }

    override fun willSaveWaitUntil(params: WillSaveTextDocumentParams): CompletableFuture<List<TextEdit>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.willSaveWaitUntil(params) }

    override fun documentLink(params: DocumentLinkParams): CompletableFuture<List<DocumentLink>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.documentLink(params)
        }

    override fun documentColor(params: DocumentColorParams): CompletableFuture<List<ColorInformation>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.documentColor(params)
        }

    override fun colorPresentation(params: ColorPresentationParams): CompletableFuture<List<ColorPresentation>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.colorPresentation(params) }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.foldingRange(params)
        }

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> =
        guardEdits(params.textDocument.uri, null) { delegate.prepareRename(params) }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<List<TypeHierarchyItem>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.prepareTypeHierarchy(params)
        }

    override fun typeHierarchySupertypes(
        params: TypeHierarchySupertypesParams,
    ): CompletableFuture<List<TypeHierarchyItem>> = if (managed(params.item.uri)) {
        CompletableFuture.completedFuture(emptyList())
    } else {
        delegate.typeHierarchySupertypes(params)
    }

    override fun typeHierarchySubtypes(
        params: TypeHierarchySubtypesParams,
    ): CompletableFuture<List<TypeHierarchyItem>> = if (managed(params.item.uri)) {
        CompletableFuture.completedFuture(emptyList())
    } else {
        delegate.typeHierarchySubtypes(params)
    }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.prepareCallHierarchy(params)
        }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): CompletableFuture<List<CallHierarchyIncomingCall>> = if (managed(params.item.uri)) {
        CompletableFuture.completedFuture(emptyList())
    } else {
        delegate.callHierarchyIncomingCalls(params)
    }

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> = if (managed(params.item.uri)) {
        CompletableFuture.completedFuture(emptyList())
    } else {
        delegate.callHierarchyOutgoingCalls(params)
    }

    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.selectionRange(params)
        }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(SemanticTokens(emptyList()))
        } else {
            delegate.semanticTokensFull(params)
        }

    override fun semanticTokensFullDelta(
        params: SemanticTokensDeltaParams,
    ): CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> = if (managed(params.textDocument.uri)) {
        CompletableFuture.completedFuture(
            Either.forLeft(SemanticTokens(emptyList())),
        )
    } else {
        delegate.semanticTokensFullDelta(params)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(SemanticTokens(emptyList()))
        } else {
            delegate.semanticTokensRange(params)
        }

    override fun moniker(params: MonikerParams): CompletableFuture<List<Moniker>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.moniker(params)
        }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> =
        guardEdits(params.textDocument.uri, emptyList()) { delegate.inlayHint(params) }

    override fun inlineValue(params: InlineValueParams): CompletableFuture<List<InlineValue>> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            delegate.inlineValue(params)
        }

    override fun diagnostic(params: DocumentDiagnosticParams): CompletableFuture<DocumentDiagnosticReport> =
        if (managed(params.textDocument.uri)) {
            CompletableFuture.completedFuture(
                DocumentDiagnosticReport(RelatedFullDocumentDiagnosticReport(emptyList())),
            )
        } else {
            delegate.diagnostic(params)
        }

    private fun <T> guardEdits(
        raw: String,
        empty: T,
        allowed: (T) -> Boolean = { true },
        request: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        if (guardedForEdit(raw)) return CompletableFuture.completedFuture(empty)
        val pending = request()
        val response = pending.thenApply { result ->
            if (result == null || !allowed(result) || guardedForEdit(raw)) {
                empty
            } else {
                result
            }
        }
        response.whenComplete { _, _ -> if (response.isCancelled) pending.cancel(true) }
        return response
    }

    private fun guardedForEdit(raw: String): Boolean =
        managed(raw) || parseUri(raw)?.let(registry::shouldGuardDocument) != false

    private fun editTargetsAllowed(targets: List<SourceEditTarget>?): Boolean =
        targets != null && targets.none { target ->
            when (target.kind) {
                SourceEditTargetKind.DOCUMENT -> registry.shouldGuardDocument(target.uri)
                SourceEditTargetKind.CREATE,
                SourceEditTargetKind.DELETE,
                SourceEditTargetKind.RENAME,
                -> registry.shouldGuardResourceOperation(target.uri)
            }
        }

    private fun managed(raw: String): Boolean = knownManaged(raw) || parseUri(raw)?.let(documents::guarded) == true

    private fun knownManaged(raw: String): Boolean =
        sourceScheme(raw) || parseUri(raw)?.let(registry::isManaged) == true

    private fun sourceScheme(raw: String): Boolean =
        raw.startsWith(ExternalSourceRegistry.SCHEME + ":", ignoreCase = true)

    private fun parseUri(raw: String): URI? = try {
        URI.create(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}
