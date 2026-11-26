// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.services.OpenDocuments
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.CompilationFailedException
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** The exact parse and the producer whose inputs must still be current to answer a query from it. */
internal class OwnedSnapshot(val capture: CapturedCompilation, val snapshot: CompiledDocumentSnapshot) {
    fun outcome(): SnapshotAcquisition {
        if (!capture.isCurrent()) return SnapshotAcquisition.Stale
        val parsed = snapshot.parseResult
        val parsedSuccessfully = parsed.ast != null && parsed.isSuccessful
        return if (parsedSuccessfully && !parsed.outcome.isDegraded && parsed.outcome.achievedPhase >= capture.phase) {
            SnapshotAcquisition.Ready(snapshot, capture::isCurrent)
        } else {
            SnapshotAcquisition.Partial(snapshot, capture::isCurrent)
        }
    }
}

/** Owns an uncached loader until a waiter receives it, or all possible recipients have gone. */
internal class PendingDelivery(private val uri: URI) {
    private var waiters = 0
    private var enrollmentClosed = false
    private var undelivered: Closeable? = null

    /** Called under the registry's URI lock; only increments the reservation count. */
    @Synchronized
    fun enroll() {
        check(!enrollmentClosed)
        waiters++
    }

    @Synchronized
    fun hold(loader: Closeable) {
        check(undelivered == null && !enrollmentClosed)
        undelivered = loader
    }

    fun finishEnrollment() = update { enrollmentClosed = true }

    fun leave(delivered: Boolean) = update {
        check(waiters > 0)
        waiters--
        if (delivered) undelivered = null
    }

    private fun update(change: () -> Unit) {
        val discarded = synchronized(this) {
            change()
            if (enrollmentClosed && waiters == 0) undelivered.also { undelivered = null } else null
        }
        try {
            discarded?.close()
        } catch (error: IOException) {
            // Cleanup can run in a deferred completion handler or while a caller's cancellation is unwinding.
            logger.warn(error) { "Failed to close unpublished compilation loader for $uri" }
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** Take the retirement fence before reading any editor, workspace or disk source. */
internal class SnapshotSource(
    val dependencies: CompilationOrchestratorDependencies,
    val uri: URI,
    val ticket: ArtifactOwnership.Ticket = dependencies.cacheService.ownership.begin(uri),
    val workspace: WorkspaceModel = dependencies.workspaceManager.current(),
) {
    private val provider = dependencies.documentProvider
    private val buffers = provider?.withLifecycleLock { provider.getBuffer(uri) to provider.captureDocuments() }
    val buffer = buffers?.first
    val inventory = buffers?.second ?: OpenDocuments.absent
    val documents = inventory.documents
    val sourceIdentity: String = buffer?.snapshot?.sourceIdentity ?: ticket.sourceIdentity
    private val worker = dependencies.workerSessionManager.current()

    private fun sameSource(other: SnapshotSource): Boolean = workspace === other.workspace &&
        buffer?.incarnation === other.buffer?.incarnation && inventory.identity === other.inventory.identity

    /** A single workspace cache retirement, with the same open inputs and no intervening file retirement. */
    fun followsCacheClear(original: SnapshotSource): Boolean {
        val prior = original.buffer ?: return false
        return buffer?.snapshot === prior.snapshot && sameSource(original) &&
            ticket.epoch > original.ticket.epoch && ticket.epoch == original.ticket.epoch.next() &&
            ticket.lastRetirement == original.ticket.lastRetirement
    }

    fun matches(other: SnapshotSource): Boolean = ticket.sharesInputs(other.ticket) && sameSource(other)

    fun canRetryWith(other: SnapshotSource): Boolean = buffer != null && worker !== other.worker &&
        sameSource(other) && other.isCurrent()

    fun isCurrent(): Boolean = dependencies.workspaceManager.current() === workspace &&
        provider?.getIncarnation(uri) === buffer?.incarnation &&
        inventory.identity === (provider?.captureDocuments() ?: OpenDocuments.absent).identity &&
        dependencies.cacheService.ownership.inputsCurrent(ticket)

    fun unlessRetired(outcome: SnapshotAcquisition) = if (isCurrent()) outcome else SnapshotAcquisition.Stale
}

/**
 * One worker invocation's inputs. Editor snapshots are compared by identity, including equal-text reopens.
 * Disk inputs use the owning fence's conservative retirement check, including implicit source-root reads.
 */
internal class CapturedCompilation(
    private val selectSources: (URI, WorkspaceModel) -> List<Path>,
    val source: SnapshotSource,
    val content: String,
    val phase: Int,
    val mode: ParseMode,
    resolvedContext: DocumentCompileContext? = null,
) {
    private val dependencies = source.dependencies
    val uri: URI get() = source.uri
    val sourceIdentity: String by lazy { "${source.sourceIdentity}/${contentHash(content)}" }
    val delivery = PendingDelivery(uri)
    val ticket: ArtifactOwnership.Ticket get() = source.ticket
    private val workspace = source.workspace
    private val ownership = dependencies.cacheService.ownership
    private val sourcePath = UriPathConverter.toPath(uri)
    private val workspaceManager = dependencies.workspaceManager
    private val session = dependencies.workerSessionManager.current()
    val context = resolvedContext ?: workspaceManager.compileContextFor(uri, content, workspace).captureClasspath()
    private val sources = selectSources(uri, workspace)
    private val locator = dependencies.symbolIndexer.classLocator(sourcePath)
    private val lookups = ConcurrentHashMap<String, List<Path>>()

    /** Freeze only what this finished invocation consumed. The retained witness owns no compiler or source text. */
    fun referenceProducer(result: ParseResult): ReferenceProducer = ReferenceProducer(
        context.classpath,
        session,
        ReferenceSources(
            workspace,
            ticket,
            source.inventory.identity,
            mode,
            sources,
            lookups.mapValues { (_, answer) -> answer.toList() },
        ),
        result.isSuccessful && !result.outcome.isDegraded,
    )

    /** Repeated lookups see their first answer, including a miss. Only names this worker asks for are retained. */
    private fun locate(name: String): List<Path> = lookups.computeIfAbsent(name, locator)

    /** Constant-time check under the ownership fence, including watched source changes outside the selected list. */
    fun ticketsCurrent(): Boolean = ownership.inputsCurrent(ticket)

    fun isCurrent(): Boolean {
        if (!ticketsCurrent()) return false
        return context == workspaceManager.compileContextFor(uri, content, workspace) &&
            sources == selectSources(uri, workspace) &&
            lookups.all { (name, answer) -> locator(name) == answer } &&
            dependencies.workerSessionManager.current() === session && source.isCurrent()
    }

    /** A current fallback still belongs to the workspace, retirement and classpath its requester selected. */
    fun canServe(requester: CompilationRequestContext): Boolean = workspace === requester.workspace &&
        ticket.sharesInputs(requester.ticket) &&
        context == requester.document &&
        isCurrent()

    /** The effective compiler inputs, including the classpath selected by a strategy for this exact content. */
    fun sameCompilerInputs(other: CapturedCompilation): Boolean = phase == other.phase && mode == other.mode &&
        content == other.content && context == other.context && session === other.session && sources == other.sources

    /** Reuse the original producer, including the retirement stamp and resolver answers it actually observed. */
    fun canShare(producer: CapturedCompilation): Boolean =
        source.matches(producer.source) && sameCompilerInputs(producer) && producer.isCurrent()

    suspend fun parse(): ParseResult {
        val job = currentCoroutineContext()
        job.ensureActive()
        val request = ParseRequest(
            uri = uri,
            content = content,
            classpath = context.classpath,
            sourceRoots = workspace.sourceRoots,
            workspaceSources = sources,
            openSources = source.documents.filterKeys { it != uri }.mapValues { it.value.content },
            locatorCandidates = compilationLocatorCandidates(uri, sourcePath),
            compilePhase = phase,
            parseMode = mode,
            checkCancelled = { job.ensureActive() },
            workspaceClassLocator = if (mode == ParseMode.MINIMAL) ::locate else null,
        )
        return session.parse(request)
    }
}

/** Source whose ownership guard applies after acquisition, including a successful startup retry. */
internal class SourceAcquisition(val source: SnapshotSource, private val outcome: SnapshotAcquisition) {
    suspend fun <T> query(compute: suspend (CompiledDocumentSnapshot) -> T): SnapshotQuery<T> = when (outcome) {
        is SnapshotAcquisition.Available -> outcome.query(compute)
        SnapshotAcquisition.Missing -> SnapshotQuery.Missing
        SnapshotAcquisition.Stale -> SnapshotQuery.Stale
        is SnapshotAcquisition.Failure -> SnapshotQuery.Failure(outcome.cause)
    }
}

internal class SnapshotAcquirer(
    private val dependencies: CompilationOrchestratorDependencies,
    private val selectSources: (URI, WorkspaceModel) -> List<Path>,
) {
    private val cache = dependencies.cacheService

    /** Fence all source inputs before preparing the workspace reference index. */
    fun referenceSource(uri: URI): SnapshotSource = SnapshotSource(dependencies, uri)

    suspend fun acquire(uri: URI, phase: Int, mode: ParseMode?): SnapshotAcquisition =
        whileCallerActive { acquireCurrent(capture(uri), phase, mode) }

    fun capture(uri: URI): SnapshotSource = SnapshotSource(dependencies, uri)

    /** Retry only an unchanged open source after a real worker replacement, using a wholly fresh producer. */
    fun captureWorkerReplacement(original: SnapshotSource): SnapshotSource? {
        val fresh = capture(original.uri)
        return fresh.takeIf(original::canRetryWith)
    }

    /**
     * Hover may replace one retired open-buffer producer before querying it. MINIMAL parses can consume
     * locator answers while running, so closed files use their original acquisition without a retry.
     */
    suspend fun acquireHover(original: SnapshotSource, phase: Int, requestCurrent: () -> Boolean): SourceAcquisition {
        if (original.buffer == null) {
            return SourceAcquisition(original, acquireSource(original, phase, ParseMode.MINIMAL))
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        dependencies.waiterScope.coroutineContext.ensureActive()
        if (!caller.inputsCurrent(original::isCurrent)) return SourceAcquisition(original, SnapshotAcquisition.Stale)

        val first = resolve(openCompilation(original, phase))
        val replacement = first.retiredProducer?.let { producer ->
            val fresh = capture(original.uri)
            if (fresh.followsCacheClear(original) && caller.inputsCurrent(requestCurrent)) {
                openCompilation(fresh, phase).takeIf(producer::sameCompilerInputs)
            } else {
                null
            }
        }
        return if (replacement != null) {
            dependencies.waiterScope.coroutineContext.ensureActive()
            // The original worker may still be unwinding. No result from it is delivered under the fresh ticket.
            SourceAcquisition(replacement.source, resolve(replacement).outcome)
        } else {
            SourceAcquisition(original, first.outcome)
        }
    }

    private fun openCompilation(source: SnapshotSource, phase: Int) = CapturedCompilation(
        selectSources,
        source,
        checkNotNull(source.buffer).snapshot.content,
        phase,
        ParseMode.WORKSPACE,
    )

    suspend fun acquireSource(source: SnapshotSource, phase: Int, mode: ParseMode?): SnapshotAcquisition =
        whileCallerActive { acquireCurrent(source, phase, mode) }

    private suspend inline fun whileCallerActive(acquire: () -> SnapshotAcquisition): SnapshotAcquisition {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        dependencies.waiterScope.coroutineContext.ensureActive()
        val result = runSuspendCatching(acquire)
        // An Available has passed checked() and may already own an uncached loader transferred by leave().
        if (result.getOrNull() !is SnapshotAcquisition.Available) caller.ensureActive()
        return result.getOrThrow()
    }

    private suspend fun acquireCurrent(source: SnapshotSource, phase: Int, mode: ParseMode?): SnapshotAcquisition {
        if (!source.isCurrent()) return SnapshotAcquisition.Stale
        val content = try {
            source.buffer?.snapshot?.content ?: UriPathConverter.toPath(source.uri)?.let { path ->
                withContext(dependencies.ioDispatcher) {
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    if (attributes.isRegularFile) Files.readString(path) else null
                }
            }
        } catch (_: NoSuchFileException) {
            null
        } catch (error: IOException) {
            return source.unlessRetired(SnapshotAcquisition.Failure(error))
        }
        return if (content == null) {
            source.unlessRetired(SnapshotAcquisition.Missing)
        } else {
            val selectedMode = mode ?: if (source.buffer != null) ParseMode.WORKSPACE else ParseMode.MINIMAL
            resolve(CapturedCompilation(selectSources, source, content, phase, selectedMode)).outcome
        }
    }

    /** Only a producer actually chosen after the initial freshness check can witness a startup retry. */
    private class Resolution(val outcome: SnapshotAcquisition, selected: CapturedCompilation? = null) {
        val retiredProducer = selected.takeIf { outcome === SnapshotAcquisition.Stale }
    }

    private suspend fun resolve(capture: CapturedCompilation): Resolution {
        currentCoroutineContext().ensureActive()
        if (!capture.isCurrent()) return Resolution(checked(SnapshotAcquisition.Stale))
        val previous = cache.getOwned(capture.uri)
        if (previous != null && capture.canShare(previous.capture)) {
            val outcome = previous.outcome()
            val answer = checked(if (capture.isCurrent()) outcome else SnapshotAcquisition.Stale)
            return Resolution(answer, previous.capture)
        }
        val pending = cache.snapshotJobs.register(capture.uri, capture, capture.ticket, capture::canShare) {
            dependencies.waiterScope.async(dependencies.ioDispatcher, start = CoroutineStart.LAZY) {
                compile(capture, previous)
            }
        }
        var answer: Resolution? = null
        try {
            answer = awaitCurrent(capture, pending)
            return answer
        } finally {
            // After this final checked handoff the caller may use the parser, even if it is cancelled later.
            pending.identity.delivery.leave(answer?.outcome is SnapshotAcquisition.Available)
        }
    }

    private suspend fun awaitCurrent(
        capture: CapturedCompilation,
        pending: PendingWork<CapturedCompilation, SnapshotAcquisition>,
    ): Resolution {
        // Registration may have raced the global map swap after the producer took its input ticket.
        if (!pending.identity.ticketsCurrent()) pending.retire()
        pending.deferred.start()
        val outcome = try {
            pending.awaitUnlessRetired()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!dependencies.waiterScope.isActive || (!pending.retired.isCompleted && pending.identity.isCurrent())) {
                throw cancelled
            }
            null
        }
        currentCoroutineContext().ensureActive()
        val current = capture.isCurrent() && pending.identity.isCurrent()
        val answer = checked(if (current && outcome != null) outcome else SnapshotAcquisition.Stale)
        return Resolution(answer, pending.identity.takeUnless { outcome is SnapshotAcquisition.Failure })
    }

    /** Strategy and source freshness lookups can run caller code or block before an answer is delivered. */
    private suspend fun checked(answer: SnapshotAcquisition): SnapshotAcquisition {
        currentCoroutineContext().ensureActive()
        return answer
    }

    private suspend fun compile(capture: CapturedCompilation, previous: OwnedSnapshot?): SnapshotAcquisition {
        if (!capture.isCurrent()) return SnapshotAcquisition.Stale
        val result = try {
            capture.parse()
        } catch (error: IOException) {
            return failure(capture, error)
        } catch (error: CompilationFailedException) {
            return failure(capture, error)
        }
        val snapshot = CompiledDocumentSnapshot(
            capture.content,
            result,
            referenceProducer = capture.referenceProducer(result),
            sourceIdentity = capture.sourceIdentity,
        )
        return publish(OwnedSnapshot(capture, snapshot), previous)
    }

    /** The worker transfers its unpublished native loader here. Published results may outlive this job. */
    private suspend fun publish(result: OwnedSnapshot, previous: OwnedSnapshot?): SnapshotAcquisition {
        val job = currentCoroutineContext().job
        val loader = result.snapshot.parseResult.compilationUnit.classLoader
        var transferred = false
        try {
            job.ensureActive()
            transferred = cache.storeSnapshot(result, previous)
            val outcome = result.outcome()
            if (!transferred && outcome is SnapshotAcquisition.Available) {
                // Another policy won the cache slot. Enrollment protects a possible delivery after this job completes.
                result.capture.delivery.hold(loader)
                transferred = true
            }
            return outcome
        } finally {
            if (!transferred) loader.close()
        }
    }

    private fun failure(capture: CapturedCompilation, error: Exception): SnapshotAcquisition =
        if (capture.isCurrent()) SnapshotAcquisition.Failure(error) else SnapshotAcquisition.Stale
}
