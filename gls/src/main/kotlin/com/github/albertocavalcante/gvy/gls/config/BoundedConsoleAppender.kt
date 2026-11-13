// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.config

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import java.io.IOException

/**
 * Synchronous stderr output delegates encoding directly to the bounded encoder.
 * Logback's deferred preparation would eagerly format arbitrary arguments before encoding.
 * The inherited writer retains its stream lock, immediate-flush policy and stop check.
 * Logger-side event and throwable-proxy construction happens before this boundary.
 */
class BoundedConsoleAppender : ConsoleAppender<ILoggingEvent>() {
    override fun subAppend(event: ILoggingEvent) {
        if (!isStarted) return
        try {
            writeOut(event)
        } catch (failure: IOException) {
            started = false
            addError("IO failure in appender", failure)
        }
    }
}
