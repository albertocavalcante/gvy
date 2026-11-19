// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.library

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Owned in-memory bytes only; this does not capture a directory or authorize compiler admission. */
internal class LibrarySourceSnapshot(entries: Map<String, ByteArray>) {
    private val sources = entries.mapValues { (path, bytes) ->
        requireNormalizedPath(path)
        bytes.copyOf()
    }.toSortedMap()

    val fingerprint: String = fingerprint("gvy-library-sources-v1") {
        writeInt(sources.size)
        sources.forEach { (path, bytes) ->
            writeText(path)
            writeInt(bytes.size)
            write(bytes)
        }
    }

    fun read(path: String): ByteArray? = sources[path]?.copyOf()

    private fun requireNormalizedPath(path: String) {
        require('\u0000' !in path && '\\' !in path) { "Invalid source entry path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Source entry paths cannot be drive-qualified" }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Source entry paths must be normalized and relative"
        }
    }
}

/** Labels record resolution provenance; they do not prove freshness or grant access. */
internal data class LibraryInputIdentity(
    val alias: String,
    val resolverIdentity: String,
    val selectedRevision: String?,
    val contentFingerprint: String,
) {
    init {
        require(alias.isNotBlank() && resolverIdentity.isNotBlank()) { "Library and resolver IDs must not be blank" }
        require(contentFingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid content fingerprint" }
    }
}

/** Completeness is a caller assertion about its bounded declared closure, not dynamic runtime dependencies. */
internal enum class LibraryClosureStatus { UNKNOWN, PARTIAL, COMPLETE }

internal class LibraryInputSnapshot(
    libraries: List<LibraryInputIdentity>,
    dependencies: List<LibraryInputIdentity>,
    val closureStatus: LibraryClosureStatus,
) {
    val libraries: List<LibraryInputIdentity> = java.util.List.copyOf(libraries)
    val dependencies: List<LibraryInputIdentity> = java.util.List.copyOf(dependencies)

    val fingerprint: String = fingerprint("gvy-library-inputs-v1") {
        writeInputs(this@LibraryInputSnapshot.libraries)
        writeInputs(this@LibraryInputSnapshot.dependencies)
        writeText(closureStatus.name)
    }
}

private fun fingerprint(format: String, writeContent: DataOutputStream.() -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { output ->
        output.writeText(format)
        output.writeContent()
    }
    return HexFormat.of().formatHex(digest.digest())
}

/** Length-prefixed UTF-16 code units preserve even distinct unpaired surrogates without replacement collisions. */
private fun DataOutputStream.writeText(value: String) {
    writeInt(value.length)
    writeChars(value)
}

private fun DataOutputStream.writeInputs(inputs: List<LibraryInputIdentity>) {
    writeInt(inputs.size)
    inputs.forEach { input ->
        writeText(input.alias)
        writeText(input.resolverIdentity)
        writeBoolean(input.selectedRevision != null)
        input.selectedRevision?.let { writeText(it) }
        writeText(input.contentFingerprint)
    }
}
