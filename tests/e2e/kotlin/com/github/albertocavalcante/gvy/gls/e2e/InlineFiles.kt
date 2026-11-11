package com.github.albertocavalcante.gvy.gls.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a scenario's `workspace.files` into its temporary workspace.
 *
 * Each entry maps a workspace-relative path to the file's text. Cursor markers are stripped and
 * line endings normalized to LF before the file is written as UTF-8, so marker positions match
 * the bytes on disk. Files are written over the fixture, replacing any file at the same path.
 */
object InlineFiles {
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /** Writes [files] under [root] and returns the cursor markers found in each file, keyed by path. */
    fun write(root: Path, files: Map<String, String>): Map<String, List<CursorMarker>> {
        // Validate everything before writing anything, so a bad entry leaves no partial workspace.
        val prepared = files.mapValues { (path, content) ->
            validate(path)
            try {
                CursorMarkers.strip(normalizeLineEndings(content))
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Inline file '$path': ${ex.message}", ex)
            }
        }
        rejectSharedMarkerNames(prepared.mapValues { it.value.markers })

        prepared.forEach { (path, marked) ->
            val target = root.resolve(path)
            target.parent?.let(Files::createDirectories)
            Files.writeString(target, marked.text, Charsets.UTF_8)
        }
        return prepared.mapValues { it.value.markers }
    }

    private fun validate(path: String) {
        require(path.isNotBlank()) { "Inline file path must not be blank" }
        require(!path.startsWith("/") && !path.startsWith("\\") && !WINDOWS_DRIVE.containsMatchIn(path)) {
            "Inline file path '$path' must be relative to the workspace root"
        }
        require(path.split('/', '\\').none { it == ".." }) {
            "Inline file path '$path' must not contain '..' segments"
        }
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
