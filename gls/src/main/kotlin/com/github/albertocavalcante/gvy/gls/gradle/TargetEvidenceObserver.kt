package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

/**
 * Observes per-target dependency evidence after a usable legacy resolution, inside the same attempt.
 * Returns Legacy for tools it does not observe or when no valid observation exists; only caller
 * cancellation should propagate. [generation] is the dependency attempt that requested it (#1235).
 */
fun interface TargetEvidenceObserver {
    suspend fun observe(tool: BuildTool, workspaceRoot: Path, generation: Long): TargetDependencyEvidence
}

private val logger = KotlinLogging.logger {}
private const val NANOS_PER_MILLI = 1_000_000L

/** Any failure other than cancellation yields Legacy: an observer must never cost the usable legacy result. */
@Suppress("TooGenericExceptionCaught") // Observers wrap build tools, which fail with arbitrary exceptions.
internal suspend fun TargetEvidenceObserver.observeOrLegacy(
    tool: BuildTool,
    workspaceRoot: Path,
    generation: Long,
): TargetDependencyEvidence {
    val started = System.nanoTime()
    val evidence = try {
        observe(tool, workspaceRoot, generation)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Target observation failed; keeping legacy evidence" }
        TargetDependencyEvidence.Legacy
    }
    val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
    logger.info { "Target observation took ${elapsed}ms: ${evidence::class.simpleName}" }
    return evidence
}

/**
 * Attaches [observer]'s evidence to a usable resolution; anything else is returned unchanged. Callers run
 * it inside the resolution attempt, before publication, so the workspace model is swapped once (#1235).
 */
internal suspend fun WorkspaceResolution.withObservedTargets(
    observer: TargetEvidenceObserver?,
    tool: BuildTool?,
    workspaceRoot: Path,
    generation: Long,
): WorkspaceResolution {
    if (observer == null || tool == null || !isUsable) return this
    return copy(targetDependencies = observer.observeOrLegacy(tool, workspaceRoot, generation))
}
