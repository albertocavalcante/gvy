package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
}
