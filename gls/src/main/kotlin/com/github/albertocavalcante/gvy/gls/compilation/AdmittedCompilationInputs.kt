// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat

/** Request-local admitted paths. Only [fingerprint], never these collections, is retained by the cache. */
internal class AdmittedCompilationInputs(
    sourceRoots: List<Path>,
    workspaceSources: List<Path>,
    openSourceSnapshots: Map<URI, DocumentSnapshot>,
    private val admission: SourceInputAdmission,
) {
    val sourceRoots: List<Path> = Collections.unmodifiableList(sourceRoots.toList())
    val workspaceSources: List<Path> = Collections.unmodifiableList(workspaceSources.toList())
    val openSources: Map<URI, String> = Collections.unmodifiableMap(
        openSourceSnapshots.entries.associateTo(LinkedHashMap()) { (uri, snapshot) -> uri to snapshot.content },
    )
    val checkCancelled: () -> Unit get() = admission.checkCancelled
    val fingerprint: String = fingerprint(openSourceSnapshots)

    val allowsSource: (Path) -> Boolean = admission::allows

    fun releaseObservedPaths() = admission.releaseObservedPaths()

    private fun fingerprint(openSourceSnapshots: Map<URI, DocumentSnapshot>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ENCODING_BUFFER_BYTES)
        fun add(value: String) {
            checkCancelled()
            digest.update(value.length.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            // UTF-16 code units preserve even malformed virtual URI spellings without replacement collisions.
            var offset = 0
            while (offset < value.length) {
                checkCancelled()
                val count = minOf(buffer.size / CODE_UNIT_BYTES, value.length - offset)
                for (index in 0 until count) {
                    val character = value[offset + index]
                    buffer[index * CODE_UNIT_BYTES] = (character.code ushr BITS_PER_BYTE).toByte()
                    buffer[index * CODE_UNIT_BYTES + 1] = character.code.toByte()
                }
                digest.update(buffer, 0, count * CODE_UNIT_BYTES)
                offset += count
            }
        }
        add("gvy-source-admission-utf16-v2")
        add("source-roots")
        add(sourceRoots.size.toString())
        sourceRoots.forEach { add(it.toString()) }
        add("workspace-sources")
        add(workspaceSources.size.toString())
        workspaceSources.forEach { add(it.toString()) }
        add("open-source-snapshots")
        add(openSourceSnapshots.size.toString())
        // Keep the same order as openSources, since source order can affect duplicate declarations.
        openSourceSnapshots.forEach { (uri, snapshot) ->
            add(uri.toString())
            add(snapshot.contentHash)
        }
        return "u16v2:" + HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val ENCODING_BUFFER_BYTES = 2048
        const val CODE_UNIT_BYTES = 2
        const val BITS_PER_BYTE = 8
    }
}
