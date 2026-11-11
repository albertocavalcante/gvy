package com.github.albertocavalcante.gvy.viz.desktop.state

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

internal interface DesktopFileOperations {
    suspend fun read(file: File): String
    suspend fun write(file: File, contents: String)
}

internal object LocalDesktopFileOperations : DesktopFileOperations {
    override suspend fun read(file: File): String = file.readText()
    override suspend fun write(file: File, contents: String) = writeNewFile(file.toPath(), contents)
}

// TODO(#1690): Support metadata-safe replacement and portable atomic publication.
//   See: https://github.com/albertocavalcante/gvy/issues/1690

/** Publish a completed sibling file without replacing an existing destination. */
internal fun writeNewFile(
    destination: Path,
    contents: String,
    publish: (Path, Path) -> Unit = { target, temporary -> Files.createLink(target, temporary) },
) {
    val target = destination.toAbsolutePath()
    val temporary = Files.createTempFile(target.parent, ".gvy-export-", ".tmp")
    try {
        Files.writeString(temporary, contents)
        try {
            publish(target, temporary)
        } catch (failure: IOException) {
            throw publicationError(failure)
        } catch (unsupported: UnsupportedOperationException) {
            throw IOException("Could not save here; choose another location", unsupported)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun publicationError(failure: IOException): IOException = if (failure is FileAlreadyExistsException) {
    IOException("Destination already exists; choose a new filename", failure)
} else {
    IOException("Could not save here; choose another filename or location", failure)
}
