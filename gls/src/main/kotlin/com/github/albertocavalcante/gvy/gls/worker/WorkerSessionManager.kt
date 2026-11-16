// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

interface WorkerSession {
    fun parse(request: ParseRequest): ParseResult

    fun parseWithSourceFilter(
        request: ParseRequest,
        sourceFilter: ((Path) -> Boolean)?,
        locatorBuffers: Map<URI, String>,
    ): ParseResult {
        require(sourceFilter == null && locatorBuffers.isEmpty()) {
            "Worker does not support source visibility filtering"
        }
        return parse(request)
    }
}

class InProcessWorkerSession(private val parser: GroovyParserFacade) : WorkerSession {
    override fun parse(request: ParseRequest): ParseResult = parser.parse(request)
    override fun parseWithSourceFilter(
        request: ParseRequest,
        sourceFilter: ((Path) -> Boolean)?,
        locatorBuffers: Map<URI, String>,
    ): ParseResult = parser.parseWithSourceFilter(request, sourceFilter, locatorBuffers)
}

class WorkerSessionManager(
    private val defaultSession: WorkerSession,
    private val sessionFactory: (WorkerDescriptor) -> WorkerSession,
) {
    private val sessions = ConcurrentHashMap<String, WorkerSession>()
    private val activeSession = AtomicReference(defaultSession)

    fun select(worker: WorkerDescriptor?) {
        if (worker == null) {
            activeSession.set(defaultSession)
            return
        }
        val session = sessions.getOrPut(worker.id) { sessionFactory(worker) }
        activeSession.set(session)
    }

    fun parse(request: ParseRequest): ParseResult = activeSession.get().parse(request)
    fun parseWithSourceFilter(
        request: ParseRequest,
        sourceFilter: ((Path) -> Boolean)?,
        locatorBuffers: Map<URI, String> = emptyMap(),
    ): ParseResult = if (sourceFilter == null && locatorBuffers.isEmpty()) {
        activeSession.get().parse(request)
    } else {
        activeSession.get().parseWithSourceFilter(request, sourceFilter, locatorBuffers)
    }
}
