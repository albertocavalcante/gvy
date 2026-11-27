// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.concurrent.SharedValue
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import java.util.concurrent.ConcurrentHashMap

interface WorkerSession {
    fun parse(request: ParseRequest): ParseResult
}

class InProcessWorkerSession(private val parser: GroovyParserFacade) : WorkerSession {
    override fun parse(request: ParseRequest): ParseResult = parser.parse(request)
}

class WorkerSessionManager(
    private val defaultSession: WorkerSession,
    private val sessionFactory: (WorkerDescriptor) -> WorkerSession,
) {
    /** The selected worker (null: none) and the session that serves it, replaced as one value. */
    private class Selection(val worker: WorkerDescriptor?, val session: WorkerSession)

    private val sessions = ConcurrentHashMap<String, WorkerSession>()
    private val selection = SharedValue(Selection(null, defaultSession))

    /** The selected worker, or null when the default session parses. */
    val selectedWorker: WorkerDescriptor? get() = selection.value.worker

    /**
     * Selects [worker], or the default session for null, and returns whether that changed the selected worker.
     *
     * The worker and its session are published together, so a reader never sees one worker's descriptor with
     * another's session. Racing calls each publish a whole selection and the last one stays; each reports a change
     * against the selection it replaced.
     */
    fun select(worker: WorkerDescriptor?): Boolean {
        val session = worker?.let { selected -> sessions.getOrPut(selected.id) { sessionFactory(selected) } }
            ?: defaultSession
        return selection.exchange(Selection(worker, session)).worker != worker
    }

    internal fun current(): WorkerSession = selection.value.session

    fun parse(request: ParseRequest): ParseResult = current().parse(request)
}
