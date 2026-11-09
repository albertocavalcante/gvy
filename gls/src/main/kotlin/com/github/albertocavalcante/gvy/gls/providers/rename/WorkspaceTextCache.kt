package com.github.albertocavalcante.gvy.gls.providers.rename

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Outcome of reading one workspace file for the cross-file scan. */
internal sealed interface DiskText {
    data class Text(val text: String, val bytes: Long) : DiskText

    /** The file is larger than the remaining scan budget, so it was not read. */
    data object TooLarge : DiskText

    /** The file is gone; it cannot use anything. */
    data object Missing : DiskText

    /** The file exists but could not be read or decoded; nothing can be said about it. */
    data object Unreadable : DiskText
}

/**
 * Bounded cache of workspace file text keyed by path and validated by (lastModified, size), so repeated
 * prepareRename requests (one per F2, in some clients one per cursor move) do not re-read unchanged files.
 * Entries beyond [maxEntries] are evicted least-recently-used. Thread-safe.
 */
internal class WorkspaceTextCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private data class Stamp(val lastModified: Long, val size: Long)
    private data class Entry(val stamp: Stamp, val text: String)

    private val entries = object : LinkedHashMap<Path, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Entry>?): Boolean = size > maxEntries
    }

    val size: Int get() = synchronized(entries) { entries.size }

    /** Reads [path], from the cache when unchanged; files larger than [budgetBytes] are not read. */
    fun read(path: Path, budgetBytes: Long): DiskText {
        val stamp = stampOf(path) ?: return DiskText.Missing
        return when {
            stamp.size > budgetBytes -> DiskText.TooLarge
            else -> cached(path, stamp)?.let { DiskText.Text(it, stamp.size) } ?: load(path, stamp)
        }
    }

    private fun stampOf(path: Path): Stamp? = try {
        if (Files.isRegularFile(path)) Stamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path)) else null
    } catch (_: IOException) {
        null
    }

    private fun cached(path: Path, stamp: Stamp): String? =
        synchronized(entries) { entries[path]?.takeIf { it.stamp == stamp }?.text }

    private fun load(path: Path, stamp: Stamp): DiskText = try {
        val text = Files.readString(path)
        synchronized(entries) { entries[path] = Entry(stamp, text) }
        DiskText.Text(text, stamp.size)
    } catch (_: IOException) {
        DiskText.Unreadable
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
