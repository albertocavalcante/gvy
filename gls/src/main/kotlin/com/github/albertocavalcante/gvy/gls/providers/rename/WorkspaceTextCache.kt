// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Outcome of reading one workspace file for the cross-file scan. */
internal sealed interface DiskText {
    data class Text(val text: String) : DiskText

    /** The file is larger than the remaining scan budget, so it was not read. */
    data object TooLarge : DiskText

    /** The file is gone; it cannot use anything. */
    data object Missing : DiskText

    /** The file exists but could not be read (an I/O failure); nothing can be said about it. */
    data object Unreadable : DiskText
}

/**
 * Bounded cache of workspace file text keyed by path and validated by modification time (at the filesystem's
 * full precision) and size, so repeated
 * prepareRename requests (one per F2, in some clients one per cursor move) do not re-read unchanged files.
 *
 * Bounded by entry count and by total characters, evicting least-recently-used. A file modified within
 * [RECENT_MILLIS] is never served from the cache, because coarse modification times cannot tell a rewrite from
 * the cached version. Cleared when the workspace roots change. Thread-safe.
 */
internal class WorkspaceTextCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxChars: Long = DEFAULT_MAX_CHARS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Stamp(val lastModified: FileTime, val size: Long)
    private data class Entry(val stamp: Stamp, val text: String)

    private val entries = LinkedHashMap<Path, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var totalChars = 0L
    private var roots: List<Path> = emptyList()

    val size: Int get() = synchronized(entries) { entries.size }
    val chars: Long get() = synchronized(entries) { totalChars }

    /** Drops everything when the workspace changed under the cache. */
    fun rebind(workspaceRoots: List<Path>) = synchronized(entries) {
        if (workspaceRoots != roots) {
            roots = workspaceRoots
            entries.clear()
            totalChars = 0
        }
    }

    /** Reads [path], from the cache when unchanged; files larger than [budgetChars] are not read. */
    fun read(path: Path, budgetChars: Long): DiskText {
        val stamp = stampOf(path) ?: return DiskText.Missing
        if (stamp.size > budgetChars) return DiskText.TooLarge
        val recent = clock() - stamp.lastModified.toMillis() < RECENT_MILLIS
        val hit = if (recent) null else cached(path, stamp)
        return hit?.let { DiskText.Text(it) } ?: load(path, stamp)
    }

    private fun stampOf(path: Path): Stamp? = try {
        if (Files.isRegularFile(path)) Stamp(Files.getLastModifiedTime(path), Files.size(path)) else null
    } catch (_: IOException) {
        null
    }

    private fun cached(path: Path, stamp: Stamp): String? =
        synchronized(entries) { entries[path]?.takeIf { it.stamp == stamp }?.text }

    private fun load(path: Path, stamp: Stamp): DiskText = try {
        // Undecodable bytes become U+FFFD: one Latin-1 file must not make every rename unverifiable.
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        store(path, Entry(stamp, text))
        DiskText.Text(text)
    } catch (_: IOException) {
        DiskText.Unreadable
    }

    private fun store(path: Path, entry: Entry) = synchronized(entries) {
        entries.put(path, entry)?.let { totalChars -= it.text.length }
        totalChars += entry.text.length
        val eldest = entries.entries.iterator()
        while ((entries.size > maxEntries || totalChars > maxChars) && eldest.hasNext()) {
            totalChars -= eldest.next().also { eldest.remove() }.value.text.length
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20_000
        const val DEFAULT_MAX_CHARS = 64L * 1024 * 1024
        const val RECENT_MILLIS = 2_000L
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
