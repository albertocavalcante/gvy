// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every

/** Preserve legacy parse expectations while the orchestrator retains one concrete worker session. */
internal fun WorkerSessionManager.delegateCapturedSessionToParseStub() {
    val manager = this
    val session = object : WorkerSession {
        override fun parse(request: ParseRequest): ParseResult = manager.parse(request)
    }
    every { manager.current() } returns session
}
