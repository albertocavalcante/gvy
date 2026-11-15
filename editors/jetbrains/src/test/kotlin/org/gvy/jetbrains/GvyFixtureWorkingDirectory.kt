// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Materialize only missing fixture directories beneath the canonical test temporary root. */
internal fun ensureFixtureWorkingDirectory(
    project: Project,
    owner: Disposable,
) {
    val directory = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    if (!Files.exists(directory)) {
        val pending = mutableListOf<Path>()
        var ancestor = directory
        while (!Files.exists(ancestor)) {
            pending.add(ancestor)
            ancestor = requireNotNull(ancestor.parent) { "No existing ancestor for fixture project" }
        }
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        check(ancestor.toRealPath().startsWith(tempRoot)) {
            "Refusing to create fixture working directory outside the test temporary root: $directory"
        }
        val created = mutableListOf<Path>()
        Disposer.register(owner) {
            // Delete only directories created by this test; non-empty directories fail closed.
            created.asReversed().forEach { Files.deleteIfExists(it) }
        }
        pending.asReversed().forEach { entry ->
            try {
                Files.createDirectory(entry)
                created.add(entry)
            } catch (_: FileAlreadyExistsException) {
                check(Files.isDirectory(entry)) { "Fixture path is not a directory: $entry" }
            }
        }
    }
    check(Files.isDirectory(directory)) { "The fixture project working directory must exist: $directory" }
}
