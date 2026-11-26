// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import java.net.URI
import java.nio.file.Path

/** The physical reads and lookups made before recovery replaced a compiler unit and closed its loader. */
class NativeRetryInputs internal constructor(
    private val discarded: List<Attempt>,
    private val latest: Attempt,
    private val complete: Boolean,
) {
    internal class Attempt(val reads: List<CompiledDocumentInput.DiskRead?>, val membership: NativeSourceMembership?)

    val hasDiscarded: Boolean get() = discarded.isNotEmpty()

    /** Verify the real attempts, including reads that raised a syntax error before any ModuleNode existed. */
    fun check(
        excludedDisk: Set<Path>,
        editorText: (URI) -> String?,
        checkCancelled: () -> Unit,
    ): NativeSourceMembership.Status {
        checkCancelled()
        if (!complete) return NativeSourceMembership.Status.UNAVAILABLE
        val outstanding = excludedDisk.toMutableSet()
        for (attempt in discarded) {
            for (read in attempt.reads) {
                checkCancelled()
                if (read == null) return NativeSourceMembership.Status.UNAVAILABLE
                if (!read.matches(editorText(read.uri), checkCancelled)) return NativeSourceMembership.Status.CHANGED
                outstanding.remove(Path.of(read.uri).toAbsolutePath().normalize())
            }
            val membership = attempt.membership ?: return NativeSourceMembership.Status.UNAVAILABLE
            val status = membership.check(checkCancelled)
            if (status != NativeSourceMembership.Status.CURRENT) return status
        }
        return if (outstanding.isEmpty()) {
            NativeSourceMembership.Status.CURRENT
        } else {
            NativeSourceMembership.Status.UNAVAILABLE
        }
    }

    companion object {
        fun of(parsed: ParseResult): NativeRetryInputs? = (parsed.sourceUnit.source as? DocumentReaderSource)?.attempts

        internal fun retainDiscarded(previous: NativeRetryInputs?, replacement: ParseResult) {
            val source = replacement.sourceUnit.source as? DocumentReaderSource ?: return
            val later = source.attempts ?: return
            source.attempts = NativeRetryInputs(
                previous?.let { it.discarded + it.latest }.orEmpty() + later.discarded,
                later.latest,
                previous?.complete == true && later.complete,
            )
        }
    }
}

/** Per compiler unit, detached before publication. Compiler callbacks cannot append to a finished receipt. */
internal class AttemptInputs {
    private val reads = mutableListOf<CompiledDocumentInput.DiskRead?>()
    private var complete = true
    var membership: NativeSourceMembership? = null

    @Synchronized
    fun record(read: CompiledDocumentInput.DiskRead?) {
        if (complete) reads.add(read)
    }

    @Synchronized
    fun finish(): NativeRetryInputs {
        complete = false
        val attempt = NativeRetryInputs.Attempt(reads.toList(), membership)
        reads.clear()
        membership = null
        return NativeRetryInputs(emptyList(), attempt, true)
    }
}
