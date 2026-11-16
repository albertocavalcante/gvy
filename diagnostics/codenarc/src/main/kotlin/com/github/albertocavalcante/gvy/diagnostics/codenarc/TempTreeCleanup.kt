// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import java.nio.file.Path

/**
 * Registers a JVM shutdown hook that deletes [dir] and everything below it.
 *
 * `File.deleteOnExit` cannot remove a non-empty directory, so the cached ruleset files would otherwise outlive
 * every process. Returns the hook so callers and tests can inspect or run it.
 */
internal fun registerTreeCleanup(
    dir: Path,
    register: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
): Thread {
    val hook = Thread({ dir.toFile().deleteRecursively() }, "codenarc-temp-cleanup")
    register(hook)
    return hook
}
