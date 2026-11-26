// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

interface WorkerSession {
    fun parse(request: ParseRequest): ParseResult
}

class InProcessWorkerSession(private val parser: GroovyParserFacade) : WorkerSession {
    override fun parse(request: ParseRequest): ParseResult = parser.parse(request)
}

class WorkerSessionManager internal constructor(
    private val defaultSession: WorkerSession,
    private val platformParent: Boolean,
    private val sessionFactory: (WorkerDescriptor) -> WorkerSession,
) {
    constructor(defaultSession: WorkerSession, sessionFactory: (WorkerDescriptor) -> WorkerSession) :
        this(defaultSession, true, sessionFactory)

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

    internal fun allowsIsolatedInputs(session: WorkerSession): Boolean = platformParent && session === defaultSession

    internal fun current(): WorkerSession = activeSession.get()

    fun parse(request: ParseRequest): ParseResult = current().parse(request)
}
