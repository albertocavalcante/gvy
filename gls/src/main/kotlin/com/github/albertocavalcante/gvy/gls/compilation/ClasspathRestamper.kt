package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Notices classpath entries of the observed targets that change on disk between dependency observations (#1364): a
 * SNAPSHOT jar republished to mavenLocal, or a class directory a composite build rewrote. A change publishes the
 * re-stamped evidence ([GroovyCompilationService.restampTargets]), so the affected target keys change and caches are
 * dropped, within one [interval] for a jar and one [debounce] after a watcher event for a directory.
 *
 * Cost is bounded by design:
 * - every [interval], one `stat` per distinct file (jar) entry, and no directory walk;
 * - a directory entry is walked only when a watcher event lands under it ([onWatchedPaths]), at most once per
 *   [debounce] for a burst of events. Only directories inside the workspace get events; one outside it is noticed
 *   at the next dependency observation.
 *
 * Nothing here runs on a request: compile keys are read from the published model only. Without target evidence
 * (the legacy flat classpath, #1450) a tick does nothing.
 */
@Suppress("LongParameterList") // the scheduler and the IO seams are injected so tests drive ticks and count I/O
class ClasspathRestamper internal constructor(
    private val compilationService: GroovyCompilationService,
    private val scope: CoroutineScope,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stat: (Path) -> EntryStat = ClasspathRestamp::statEntry,
    private val walk: (Path) -> ArtifactStamp? = ArtifactStamp::of,
) {
    private val logger = KotlinLogging.logger {}

    /** One re-stamp at a time, so a tick and an event drain never publish against each other's evidence. */
    private val mutex = Mutex()
    private val lock = Any()
    private var ticking: Job? = null
    private val pending = LinkedHashSet<Path>()
    private var draining: Job? = null

    /** Starts the periodic jar tick; idempotent. It ends with [scope]. */
    fun start() = synchronized(lock) {
        if (ticking != null) return@synchronized
        ticking = scope.launch(dispatcher) {
            while (isActive) {
                delay(interval)
                runSuspendCatching { tick() }.onFailure { logger.warn(it) { "Classpath re-stamp tick failed" } }
            }
        }
    }

    /** Re-stats every file entry of the current target evidence once; returns whether new evidence was published. */
    suspend fun tick(): Boolean = mutex.withLock {
        val evidence = scoped() ?: return@withLock false
        val refreshed = ClasspathRestamp.restampFiles(evidence, stat) ?: return@withLock false
        publish(evidence, refreshed, "jar")
    }

    /**
     * Watcher events: the directory entries any of [paths] lie under are re-walked after [debounce]. The check that a
     * path is under a directory entry is in memory; events elsewhere cost nothing.
     */
    fun onWatchedPaths(paths: Collection<Path>) {
        val evidence = scoped() ?: return
        val directories = ClasspathRestamp.directoriesContaining(evidence, paths)
        if (directories.isEmpty()) return
        synchronized(lock) {
            pending += directories
            if (draining?.isActive != true) {
                draining = scope.launch(dispatcher) {
                    delay(debounce)
                    runSuspendCatching { drain() }
                        .onFailure { logger.warn(it) { "Classpath directory re-stamp failed" } }
                }
            }
        }
    }

    /** Re-walks the directories queued by [onWatchedPaths]; returns whether new evidence was published. */
    internal suspend fun drain(): Boolean {
        val directories = synchronized(lock) { pending.toSet().also { pending.clear() } }
        if (directories.isEmpty()) return false
        return mutex.withLock {
            val evidence = scoped() ?: return@withLock false
            val refreshed = ClasspathRestamp.restampDirectories(evidence, directories, walk) ?: return@withLock false
            publish(evidence, refreshed, "class directory")
        }
    }

    /** The in-workspace directory entries a watcher should report changes under, for the watcher registrar. */
    fun watchedDirectories(workspaceRoot: Path?): List<Path> {
        val root = workspaceRoot?.toAbsolutePath()?.normalize() ?: return emptyList()
        val evidence = scoped() ?: return emptyList()
        return evidence.snapshots.asSequence()
            .flatMap { it.artifacts }
            .filter { it.stamp?.files != null }
            .map { it.path.toAbsolutePath().normalize() }
            .filter { it.startsWith(root) && it != root }
            .distinct()
            .toList()
    }

    private fun scoped(): TargetDependencyEvidence.Scoped? =
        compilationService.workspaceManager.current().targets as? TargetDependencyEvidence.Scoped

    private fun publish(
        evidence: TargetDependencyEvidence.Scoped,
        refreshed: TargetDependencyEvidence.Scoped,
        what: String,
    ): Boolean = compilationService.restampTargets(evidence, refreshed).also { published ->
        if (published) logger.info { "A $what on the classpath changed on disk; target compile keys refreshed" }
    }

    companion object {
        /** How often jar entries are re-stat'ed: one `stat` each. */
        val DEFAULT_INTERVAL: Duration = 10.seconds

        /** How long a burst of watcher events under a class directory is gathered before one walk. */
        val DEFAULT_DEBOUNCE: Duration = 500.milliseconds
    }
}
