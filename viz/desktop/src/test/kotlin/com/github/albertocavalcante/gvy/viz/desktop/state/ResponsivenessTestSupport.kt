// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import kotlinx.coroutines.CoroutineDispatcher
import java.awt.EventQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Test-only dispatcher that waits for a real worker to finish before dispatch returns. */
internal object CompletesBeforeReturnDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val thread = Thread(block, "fast-completion-test")
        thread.isDaemon = true
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(5))
        check(!thread.isAlive) { "Worker did not finish in time" }
    }
}

/** Observe AWT-dispatched completions after the callback has applied its state. */
internal class ObservingAwtDispatcher(private val afterDispatch: () -> Unit) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        EventQueue.invokeLater {
            block.run()
            afterDispatch()
        }
    }
}
