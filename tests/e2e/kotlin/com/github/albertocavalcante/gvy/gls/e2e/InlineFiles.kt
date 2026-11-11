package com.github.albertocavalcante.gvy.gls.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a scenario's `workspace.files` into its temporary workspace.
 *
 * Each entry maps a workspace-relative path to the file's text. Paths are normalized to `/`-separated
 * form without `.` or empty segments, so `./a//B.groovy` and `a/B.groovy` name the same file and
 * may not both appear. Cursor markers are stripped and
 * line endings normalized to LF before the file is written as UTF-8, so marker positions match
 * the bytes on disk. Files are written over the fixture, replacing any file at the same path.
 */
object InlineFiles {
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /** Writes [files] under [root] and returns the cursor markers found in each file, keyed by normalized path. */
    fun write(root: Path, files: Map<String, String>): Map<String, List<CursorMarker>> {
        // Validate everything before writing anything, so a bad entry leaves no partial workspace.
        val normalized = normalizePaths(root, files.keys)
        val prepared = files.entries.associate { (path, content) ->
            val marked = try {
                CursorMarkers.strip(normalizeLineEndings(content))
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Inline file '$path': ${ex.message}", ex)
            }
            normalized.getValue(path) to marked
        }
        rejectSharedMarkerNames(prepared.mapValues { it.value.markers })

        prepared.forEach { (path, marked) ->
            val target = root.resolve(path)
            target.parent?.let(Files::createDirectories)
            Files.writeString(target, marked.text, Charsets.UTF_8)
        }
        return prepared.mapValues { it.value.markers }
    }

    /** Maps each declared path to its normalized form, rejecting paths that are unsafe or collide. */
    private fun normalizePaths(root: Path, paths: Collection<String>): Map<String, String> {
        val normalized = paths.associateWith { normalize(root, it) }
        normalized.entries.groupBy({ it.value }, { it.key }).values.firstOrNull { it.size > 1 }?.let { same ->
            throw IllegalArgumentException("Inline file paths $same name the same file")
        }
        return normalized
    }

    private fun normalize(root: Path, path: String): String {
        require(path.isNotBlank()) { "Inline file path must not be blank" }
        require(!path.startsWith("/") && !path.startsWith("\\") && !WINDOWS_DRIVE.containsMatchIn(path)) {
            "Inline file path '$path' must be relative to the workspace root"
        }
        val segments = path.split('/', '\\')
        require(segments.none { it == ".." }) { "Inline file path '$path' must not contain '..' segments" }
        val kept = segments.filter { it.isNotEmpty() && it != "." }
        require(kept.isNotEmpty() && !path.endsWith("/") && !path.endsWith("\\")) {
            "Inline file path '$path' must name a file, not a directory"
        }
        val relative = kept.joinToString("/")
        require(!Files.isDirectory(root.resolve(relative))) {
            "Inline file path '$path' must name a file, but the fixture has a directory there"
        }
        return relative
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun rejectSharedMarkerNames(markersByPath: Map<String, List<CursorMarker>>) {
        markersByPath
            .flatMap { (path, markers) -> markers.map { it.label to path } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .entries
            .firstOrNull()
            ?.let { (label, paths) ->
                throw IllegalArgumentException("Cursor marker '$label' appears in more than one inline file: $paths")
            }
    }
}
