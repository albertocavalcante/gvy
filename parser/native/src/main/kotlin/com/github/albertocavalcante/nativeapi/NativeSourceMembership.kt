// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.ast.ModuleNode
import java.io.IOException
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Physical candidates for script resources the compiler actually searched for under directory URLs. */
class NativeSourceMembership internal constructor(
    private val files: Map<Path, FileState>?,
    private val retiredDuringLookup: Boolean,
) {
    internal val isolated: Boolean get() = files?.isEmpty() == true && !retiredDuringLookup

    enum class Status { CURRENT, CHANGED, UNAVAILABLE }

    fun check(checkCancelled: () -> Unit): Status {
        if (retiredDuringLookup) return Status.CHANGED
        val known = files ?: return Status.UNAVAILABLE
        for ((path, before) in known) {
            checkCancelled()
            val now = FileState.observe(path) ?: return Status.UNAVAILABLE
            if (now != before) return Status.CHANGED
        }
        return Status.CURRENT
    }

    companion object {
        fun of(module: ModuleNode): NativeSourceMembership? = module.getNodeMetaData(NativeSourceMembership::class.java)
    }
}

/** Only a definite missing file/path component is absence. Other IO failures cannot certify a lookup. */
internal sealed interface FileState {
    data object Absent : FileState
    data class Present(val canonical: Path) : FileState

    companion object {
        fun observe(path: Path): FileState? = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attributes.isRegularFile) readableFile(path) else null
        } catch (_: NoSuchFileException) {
            Absent
        } catch (_: NotDirectoryException) {
            Absent
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        // A file that disappeared or became unreadable after stat cannot certify a stable absence.
        private fun readableFile(path: Path): FileState? = try {
            Files.newByteChannel(path).use { Present(path.toFile().canonicalFile.toPath()) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

internal class SourceMembershipRecorder(
    /** Actual ordered directory entries of this compiler loader. Archive indexing is a separate contract. */
    private val directories: List<Path>,
    private val limit: Int = MAX_CANDIDATES,
    private val state: (Path) -> FileState? = { FileState.observe(it) },
    private val localLoader: Boolean = true,
) {
    private var complete = true
    private var retired = false
    private var pathCharacters = 0L
    private val files = linkedMapOf<Path, FileState>()
    private val searchPaths = ThreadLocal<MutableSet<Path>>()

    fun scriptSearch(lookup: () -> URL?): URL? {
        if (searchPaths.get() != null) return lookup()
        val consulted = linkedSetOf<Path>()
        searchPaths.set(consulted)
        var returned = false
        return try {
            lookup().also { answer ->
                returned = true
                verifyAnswer(answer, consulted)
            }
        } finally {
            try {
                if (!returned) unavailable()
                consulted.forEach(::observe)
            } finally {
                searchPaths.remove()
            }
        }
    }

    /** Capture the physical candidates before the real lookup. scriptSearch checks again after Groovy selects. */
    fun resource(name: String, lookup: () -> URL?): URL? {
        val consulted = searchPaths.get() ?: return lookup()
        val paths = observeSearchPrefix(candidates(name))
        consulted.addAll(paths)
        var returned = false
        return try {
            lookup().also { answer ->
                returned = true
                verifyResource(answer, paths)
            }
        } finally {
            // Groovy's extension probe can swallow an exception from getResource.
            if (!returned) unavailable()
        }
    }

    @Synchronized
    private fun verifyResource(answer: URL?, paths: List<Path>) {
        val first = paths.firstOrNull { files[it] is FileState.Present }
        val selected = answer?.let(::selectedPath)
        if (first != selected || (answer != null && selected == null)) complete = false
    }

    @Synchronized
    private fun verifyAnswer(answer: URL?, paths: Set<Path>) {
        if (!localLoader || (answer == null && paths.any { files[it] is FileState.Present })) {
            complete = false
        } else if (answer != null && selectedPath(answer) !in paths) {
            // A script selected from an archive or dynamic parent is outside this physical directory receipt.
            complete = false
        }
    }

    private fun selectedPath(answer: URL): Path? = try {
        if (answer.protocol == "file") Path.of(answer.toURI()).toAbsolutePath().normalize() else null
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Synchronized
    private fun unavailable() {
        complete = false
    }

    @Synchronized
    private fun candidates(name: String): List<Path> {
        if (!complete || directories.isEmpty()) return emptyList()
        val relative = try {
            Path.of(name)
        } catch (_: InvalidPathException) {
            null
        }
        val withinLimits = name.length <= MAX_RESOURCE_NAME && directories.size <= limit
        val safeRelative = relative?.takeUnless { it.isAbsolute || it.normalize() != it || it.startsWith("..") }
        if (!withinLimits || safeRelative == null) {
            complete = false
            return emptyList()
        }
        return directories.map { it.resolve(safeRelative) }
    }

    /** A selected directory resource hides later entries. An opaque or inconsistent selection is unavailable. */
    @Synchronized
    private fun observeSearchPrefix(candidates: List<Path>): List<Path> {
        val consulted = mutableListOf<Path>()
        for (path in candidates) {
            consulted.add(path)
            observe(path)
            if (!complete || files[path] is FileState.Present) break
        }
        return consulted
    }

    @Synchronized
    private fun observe(path: Path) {
        val before = files[path]
        if (!complete && before == null) return
        if (before == null) pathCharacters += path.toString().length
        if ((before == null && files.size >= limit) || pathCharacters > MAX_PATH_CHARACTERS) {
            complete = false
            return
        }
        val seen = state(path)
        when {
            seen == null -> complete = false
            before != null && before != seen -> {
                retired = true
                complete = false
            }
            complete -> files[path] = seen
        }
    }

    @Synchronized
    fun finish(): NativeSourceMembership = NativeSourceMembership(if (complete) files.toMap() else null, retired)

    companion object {
        private const val MAX_CANDIDATES = 4096
        private const val MAX_RESOURCE_NAME = 4096
        private const val MAX_PATH_CHARACTERS = 512 * 1024
    }
}
