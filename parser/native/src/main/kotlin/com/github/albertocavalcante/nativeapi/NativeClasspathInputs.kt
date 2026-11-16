// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import kotlinx.collections.immutable.toPersistentList
import java.nio.file.Path

/**
 * Ordered inputs and bounded observations captured before configuration or loader creation.
 * Values own no resources.
 */
class NativeClasspathInputs(
    classpath: Iterable<Path>,
    sourceRoots: Iterable<Path>,
    classpathStamps: Iterable<NativeArtifactStamp?>? = null,
    val compilerSdk: NativeCompilerSdkIdentity? = null,
    val bootJdk: NativeJdkIdentity? = null,
) {
    val classpath: List<Path> = classpath.toPersistentList()
    val sourceRoots: List<Path> = sourceRoots.toPersistentList()
    val classpathStamps: List<NativeArtifactStamp?> =
        classpathStamps?.toPersistentList() ?: this.classpath.map { null }.toPersistentList()

    init {
        require(this.classpath.size == this.classpathStamps.size)
    }

    fun withCompilerSdk(identity: NativeCompilerSdkIdentity?): NativeClasspathInputs =
        NativeClasspathInputs(classpath, sourceRoots, classpathStamps, identity, bootJdk)

    companion object {
        const val MAX_CLASSPATH_OBSERVATION_PATHS = 16_384
        const val MAX_CLASSPATH_CONTENT_IDENTITY_BYTES = 512L * 1024 * 1024

        /** Copy caller collections before callbacks; retain unknown evidence instead of a partial tree stamp. */
        fun capture(
            classpath: Iterable<Path>,
            sourceRoots: Iterable<Path>,
            checkCancelled: () -> Unit = {},
            maxObservedPaths: Int = MAX_CLASSPATH_OBSERVATION_PATHS,
            maxContentBytes: Long = MAX_CLASSPATH_CONTENT_IDENTITY_BYTES,
        ): NativeClasspathInputs {
            require(maxObservedPaths > 0)
            require(maxContentBytes >= 0)
            val frozen = NativeClasspathInputs(classpath, sourceRoots)
            var remaining = maxObservedPaths
            var remainingContentBytes = maxContentBytes
            var complete = true
            val stamps = frozen.classpath.map { path ->
                if (!complete || remaining <= 0) {
                    null
                } else {
                    val observed = NativeArtifactStamp.observe(
                        path,
                        maxPaths = minOf(remaining, NativeArtifactStamp.MAX_DIRECTORY_PATHS),
                        checkCancelled = checkCancelled,
                        maxContentBytes = remainingContentBytes,
                    )
                    if (observed == null || observed.contentIdentity != null && observed.size > remainingContentBytes) {
                        complete = false
                        null
                    } else {
                        remaining -= observed.observedPaths
                        if (observed.contentIdentity != null) remainingContentBytes -= observed.size
                        observed
                    }
                }
            }
            val bootJdk = NativeJdkIdentity.observe(checkCancelled)
            return NativeClasspathInputs(frozen.classpath, frozen.sourceRoots, stamps, bootJdk = bootJdk)
        }
    }
}
