// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.nio.file.Path

/** Detached reads and lookups of the returned compiler unit and every unit replaced during recovery. */
class NativeRetryInputs internal constructor(
    private val discarded: List<Attempt>,
    private val latest: Attempt,
    private val complete: Boolean,
) {
    internal data class Attempt(
        val reads: List<CompiledDocumentInput.DiskRead?>,
        val documents: List<Pair<URI, String>>,
        val membership: NativeSourceMembership?,
        val binary: NativeBinaryInputs?,
    ) {
        fun isolated(uri: URI): Boolean =
            reads.isEmpty() && documents.all { it.first == uri } &&
                membership?.isolated == true && binary?.isolated == true
    }

    fun isolated(uri: URI): Boolean = complete && latest.isolated(uri) && discarded.all { it.isolated(uri) }

    fun owns(module: ModuleNode): Boolean =
        latest.membership != null && NativeSourceMembership.of(module) === latest.membership

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
            val status = attempt.checkInputs(outstanding, editorText, checkCancelled)
            if (status != NativeSourceMembership.Status.CURRENT) return status
        }
        return if (outstanding.isEmpty()) {
            latest.checkInputs(outstanding, editorText, checkCancelled)
        } else {
            NativeSourceMembership.Status.UNAVAILABLE
        }
    }

    private fun Attempt.checkInputs(
        outstanding: MutableSet<Path>,
        editorText: (URI) -> String?,
        checkCancelled: () -> Unit,
    ): NativeSourceMembership.Status {
        for ((uri, text) in documents) {
            checkCancelled()
            if (editorText(uri) != text) return NativeSourceMembership.Status.CHANGED
        }
        for (read in reads) {
            checkCancelled()
            if (read == null) return NativeSourceMembership.Status.UNAVAILABLE
            if (!read.matches(editorText(read.uri), checkCancelled)) return NativeSourceMembership.Status.CHANGED
            outstanding.remove(Path.of(read.uri).toAbsolutePath().normalize())
        }
        val status = membership?.check(checkCancelled) ?: NativeSourceMembership.Status.UNAVAILABLE
        if (status != NativeSourceMembership.Status.CURRENT) return status
        return binary?.check(checkCancelled) ?: NativeSourceMembership.Status.UNAVAILABLE
    }

    companion object {
        fun of(parsed: ParseResult): NativeRetryInputs? {
            val history = (parsed.sourceUnit.source as? DocumentReaderSource)?.attempts ?: return null
            val binary = parsed.compilationUnit.classLoader.nativeBinaryInputs()
            return NativeRetryInputs(history.discarded, history.latest.copy(binary = binary), history.complete)
        }

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
    private val documents = mutableListOf<Pair<URI, String>>()
    private var complete = true
    var membership: NativeSourceMembership? = null

    @Synchronized
    fun record(read: CompiledDocumentInput.DiskRead?) {
        if (complete) reads.add(read)
    }

    @Synchronized
    fun record(document: DocumentReaderSource) {
        if (complete) documents.add(document.documentUri to document.text)
    }

    @Synchronized
    fun finish(binary: NativeBinaryInputs?): NativeRetryInputs {
        complete = false
        val attempt = NativeRetryInputs.Attempt(reads.toList(), documents.toList(), membership, binary)
        reads.clear()
        documents.clear()
        membership = null
        return NativeRetryInputs(emptyList(), attempt, true)
    }
}
