// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ResultHandler
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Independently reads current-build source-set COMPILE observations using caller-owned provenance.
 *
 * This does not attach observations to legacy resolution, choose documents, or establish editor
 * freshness. Source-set properties may differ from compiler-task classpaths; reported roots are
 * not membership. Included builds, isolated projects and older Gradle versions are not established.
 * Ordinary Gradle configuration, plugins and artifact transforms may execute; no task is requested.
 *
 * Coordinates are the exact module component that Gradle resolved for an artifact path. Gradle's
 * public API reports no artifact classifier or extension, so those remain null (unknown) and two
 * artifacts of one component share coordinates while keeping distinct paths.
 */
class GradleScopedDependencyProducer(
    private val connectionFactory: GradleConnectionFactory = GradleConnectionPool,
    private val javaHome: Path? = null,
    private val initScriptDirectory: Path? = GradleScopedInitScript.defaultDirectory(),
    private val daemonSettings: GradleDaemonSettings = GradleDaemonSettings(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Returns Scoped evidence, or Legacy when the build cannot supply a valid observation.
     *
     * Every failure reported by the Tooling API, including errors and cancellations raised by the
     * user's build, yields Legacy. Only cancellation of the calling coroutine propagates; it also
     * cancels the Gradle operation. When [timeout] elapses the operation is cancelled and Legacy
     * is returned. Neither path waits for the daemon to acknowledge cancellation.
     *
     * COMPLETE describes the enumerated property only, not complete graph or framework identity.
     * Configuration names indicate observed ordered-file equivalence, not property origin or
     * compiler-task binding. A build without any source set yields Legacy, not an empty result.
     * Connections are leased from [connectionFactory] for the operation and never closed here. Unlike legacy
     * resolution there is no isolated-Gradle-home retry: that build would omit the user's
     * `gradle.properties` and init scripts, so it would not observe the user's effective build.
     * Without an absolute user home there is no trusted script directory, which also yields Legacy.
     */
    suspend fun resolve(
        projectDirectory: Path,
        provenance: DependencyProvenance,
        timeout: Duration? = null,
    ): TargetDependencyEvidence = withContext(Dispatchers.IO) {
        val directory = initScriptDirectory
            ?: return@withContext unavailable(IOException("No absolute user home for the init script directory"))
        val script = try {
            GradleScopedInitScript.install(directory)
        } catch (failure: IOException) {
            return@withContext unavailable(failure)
        } catch (failure: UnsupportedOperationException) {
            return@withContext unavailable(failure)
        }
        if (timeout == null) {
            observe(projectDirectory, script, provenance)
        } else {
            withTimeoutOrNull(timeout) { observe(projectDirectory, script, provenance) }
                ?: unavailable(IOException("Scoped Gradle observation exceeded $timeout"))
        }
    }

    private suspend fun observe(
        projectDirectory: Path,
        script: Path,
        provenance: DependencyProvenance,
    ): TargetDependencyEvidence = attempt(projectDirectory, script, provenance).getOrElse(::unavailable)

    /** Failures from the Tooling API or local transport validation; caller cancellation is rethrown. */
    private suspend fun attempt(
        projectDirectory: Path,
        script: Path,
        provenance: DependencyProvenance,
    ): Result<TargetDependencyEvidence> = try {
        Result.success(project(fetch(projectDirectory, script), provenance))
    } catch (cancelled: CancellationException) {
        // Checked before IllegalStateException, its supertype: never swallow caller cancellation or timeout.
        throw cancelled
    } catch (failure: GradleConnectionException) {
        // Includes errors and cancellations raised inside the user's build; never walk their causes.
        coroutineContext.ensureActive()
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

    private suspend fun fetch(projectDirectory: Path, script: Path): GradleScopedDependencyModel? {
        val cancellation = GradleConnector.newCancellationTokenSource()
        // The lease spans the whole asynchronous operation so the idle sweep cannot close the connection under it.
        return connectionFactory.acquire(projectDirectory, null).use { lease ->
            // The idle timeout is what bounds the daemon this may start; it is not part of daemon compatibility, so
            // reuse of the user's own daemon is unchanged.
            val builder = lease.connection
                .model(GradleScopedDependencyModel::class.java)
                .withArguments(
                    "--no-configure-on-demand",
                    "--init-script",
                    script.toString(),
                    GradleOperationArguments.idleTimeoutArgument(daemonSettings),
                )
                .withCancellationToken(cancellation.token())
            javaHome?.let { builder.setJavaHome(it.toFile()) }
            suspendCancellableCoroutine<GradleScopedDependencyModel?> { continuation ->
                continuation.invokeOnCancellation { cancellation.cancel() }
                builder.get(
                    object : ResultHandler<GradleScopedDependencyModel> {
                        override fun onComplete(result: GradleScopedDependencyModel?) = continuation.resume(result)

                        override fun onFailure(failure: GradleConnectionException) =
                            continuation.resumeWithException(failure)
                    },
                )
            }
        }
    }

    /** Local validation failures describe the transport, not the caller, so they also yield Legacy. */
    private fun project(
        model: GradleScopedDependencyModel?,
        provenance: DependencyProvenance,
    ): TargetDependencyEvidence = try {
        // Stat each artifact once per observation, on the IO dispatcher this runs on (#1235)
        val scoped = GradleScopedDependencyProjection.project(requireNotNull(model), provenance, ArtifactStamp::of)
        if (scoped.snapshots.isEmpty()) {
            logger.info { "Gradle build reported no source sets; retaining legacy evidence" }
            TargetDependencyEvidence.Legacy
        } else {
            scoped
        }
    } catch (failure: IllegalArgumentException) {
        unavailable(failure)
    } catch (failure: IllegalStateException) {
        unavailable(failure)
    } catch (failure: ClassCastException) {
        unavailable(failure)
    } catch (failure: UnsupportedOperationException) {
        unavailable(failure)
    }

    private fun unavailable(failure: Throwable): TargetDependencyEvidence {
        logger.warn(failure) { "Scoped Gradle observation unavailable; retaining legacy evidence" }
        return TargetDependencyEvidence.Legacy
    }
}
