// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.file.Path

/** Source evidence of the original invocation. The model remains solely owned by its workspace. */
internal class ReferenceSources(
    workspace: WorkspaceModel,
    ticket: ArtifactOwnership.Ticket,
    private val buffers: Any,
    val mode: ParseMode,
    selected: List<Path>,
    private val located: Map<String, List<Path>>,
) {
    private val model = WeakReference(workspace)
    private val projection = ReferenceSourceProjection.capture(workspace.sources, selected)
    private val epoch = ticket.epoch
    private val retirement = ticket.lastRetirement

    fun ownsWindow(workspace: WorkspaceModel, ticket: ArtifactOwnership.Ticket, inventory: Any): Boolean =
        model.get() === workspace &&
            buffers === inventory &&
            epoch == ticket.epoch &&
            retirement == ticket.lastRetirement

    fun matches(other: ReferenceSources?): Boolean {
        val workspace = model.get() ?: return false
        return other != null &&
            workspace === other.model.get() &&
            buffers === other.buffers &&
            epoch == other.epoch &&
            retirement == other.retirement &&
            mode == other.mode &&
            projection?.sameAs(other.projection) == true &&
            located == other.located
    }

    fun isCurrent(source: SnapshotSource, uri: URI, selected: List<Path>): Boolean {
        if (!ownsWindow(source.workspace, source.ticket, source.inventory.identity) ||
            projection?.matches(source.workspace.sources, selected) != true
        ) {
            return false
        }
        if (located.isEmpty()) return true
        val locator = source.dependencies.symbolIndexer.classLocator(UriPathConverter.toPath(uri))
        return located.all { (name, answer) -> locator(name) == answer }
    }
}

/** The compact proof belongs to the actual worker invocation, including after an index/cache hit. */
internal class ReferenceProducer(
    private val classpath: List<Path>,
    private val session: WeakReference<WorkerSession>,
    private val sources: ReferenceSources? = null,
    val complete: Boolean = false,
) {
    constructor(
        classpath: List<Path>,
        session: WorkerSession,
        sources: ReferenceSources? = null,
        complete: Boolean = false,
    ) : this(classpath, WeakReference(session), sources, complete)

    val mode: ParseMode? get() = sources?.mode

    fun ownsWindow(workspace: WorkspaceModel, ticket: ArtifactOwnership.Ticket, inventory: Any): Boolean =
        sources?.ownsWindow(workspace, ticket, inventory) == true

    fun matches(paths: List<Path>, worker: WorkerSession): Boolean = worker === session.get() && paths == classpath

    fun sameEnvironment(other: ReferenceProducer?): Boolean {
        val worker = other?.session?.get() ?: return false
        return matches(other.classpath, worker)
    }

    fun matches(other: ReferenceProducer?): Boolean =
        sameEnvironment(other) && sources != null && sources.matches(other?.sources)

    fun isCurrent(
        source: SnapshotSource,
        uri: URI,
        content: String,
        selectSources: (URI, WorkspaceModel) -> List<Path>,
    ): Boolean {
        if (sources?.isCurrent(source, uri, selectSources(uri, source.workspace)) != true) return false
        val dependencies = source.dependencies
        val context = dependencies.workspaceManager.compileContextFor(uri, content, source.workspace)
        return matches(context.classpath, dependencies.workerSessionManager.current())
    }
}

/** The occurrence view and the original bindings required of any caller/origin parse used with it. */
internal class QualifiedReferences(
    val index: CapturedWorkspaceReferences,
    private val producers: Map<URI, ReferenceProducer>,
) {
    fun modeFor(uri: URI): ParseMode = checkNotNull(producers[uri]?.mode)

    fun matches(uri: URI, snapshot: CompiledDocumentSnapshot): Boolean =
        index.getDocumentSnapshot(uri)?.sourceText == snapshot.sourceText &&
            producers[uri]?.matches(snapshot.referenceProducer) == true
}
