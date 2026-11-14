// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.utils.SourcePathAdmission
import com.github.albertocavalcante.gvy.gls.utils.classifySourcePath
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path

/** Admission-time observations only. The compiler owns the remaining path-to-read race; this is no byte lease. */
internal class SourceInputAdmission(private val excluded: (Path) -> Boolean, val checkCancelled: () -> Unit) {
    private val decisions = mutableMapOf<Path, Boolean>()

    fun allows(path: Path): Boolean {
        checkCancelled()
        val raw = path.toAbsolutePath()
        // Check before normalizing or looking up a decision cached under a different spelling.
        if (raw.any { it.toString() == ".." }) return false
        val absolute = raw.normalize()
        return decisions.getOrPut(absolute) {
            classifySourcePath(absolute, excluded) == SourcePathAdmission.ORDINARY
        }
    }

    fun allows(uri: URI): Boolean {
        if (!uri.scheme.equals("file", ignoreCase = true)) return true
        val path = try {
            Path.of(uri)
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: FileSystemNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        return allows(path)
    }

    /** Compiler callbacks may remain on the unit; do not retain the per-parse path table with its AST. */
    fun releaseObservedPaths() {
        decisions.clear()
    }

    fun requireDocument(uri: URI) {
        require(allows(uri)) {
            "Managed or unavailable source paths cannot be compiled as workspace documents"
        }
    }
}
