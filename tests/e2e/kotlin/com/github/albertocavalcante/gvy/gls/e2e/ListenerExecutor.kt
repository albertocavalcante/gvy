// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory

/** Owns the launcher's executor and all of its worker threads. */
internal class ListenerExecutor(scenarioName: String, threadFactory: ThreadFactory? = null) : AutoCloseable {
    private val owned = OwnedExecutor(
        "groovy-lsp-e2e-$scenarioName",
        Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS),
        threadFactory,
    )
    val service: ExecutorService get() = owned.service

    override fun close() = owned.close()
}

private const val CLOSE_TIMEOUT_SECONDS = 5L
