// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.worker

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.concurrent.SharedValue
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
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
    /** The Groovy version, the worker chosen for it (null: none) and the worker's session, replaced as one value. */
    private class Selection(val version: GroovyVersionInfo?, val worker: WorkerDescriptor?, val session: WorkerSession)

    private val sessions = ConcurrentHashMap<String, WorkerSession>()
    private val selection = SharedValue(Selection(null, null, defaultSession))

    // Held only to read the selection and store its replacement, so each replacement derives from the one it
    // replaces; readers never take it.
    private val replacing = Any()

    /** The selected Groovy version, or null before any was selected. */
    val selectedVersion: GroovyVersionInfo? get() = selection.value.version

    /** The selected worker, or null when the default session parses. */
    val selectedWorker: WorkerDescriptor? get() = selection.value.worker

    /**
     * Selects [version] and [worker] (null: the default session) together, and returns whether that changed the
     * selected worker.
     *
     * The version, the worker and its session are published as one value, so a reader never sees one call's version
     * or worker with another call's. Racing calls each publish a whole selection and the last one stays; each reports
     * a change against the selection it replaced.
     */
    fun select(version: GroovyVersionInfo?, worker: WorkerDescriptor?): Boolean = replace(worker) { version }

    /** Selects [worker] as [select] does, keeping the selected version. */
    fun select(worker: WorkerDescriptor?): Boolean = replace(worker) { it.version }

    private fun replace(worker: WorkerDescriptor?, version: (Selection) -> GroovyVersionInfo?): Boolean {
        // Built before the swap. Two first uses of one id racing may each build a session: getOrPut keeps the one
        // stored first, both callers use it, and the other is discarded unused.
        val session = worker?.let { selected -> sessions.getOrPut(selected.id) { sessionFactory(selected) } }
            ?: defaultSession
        val previous = synchronized(replacing) {
            selection.value.also { current -> selection.set(Selection(version(current), worker, session)) }
        }
        return previous.worker != worker
    }

    internal fun current(): WorkerSession = selection.value.session

    fun parse(request: ParseRequest): ParseResult = current().parse(request)
}
