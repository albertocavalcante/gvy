// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import kotlinx.coroutines.CoroutineDispatcher
import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext

/** Compose Desktop and Swing state changes are confined to the AWT event dispatch thread. */
internal object AwtDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()
    override fun dispatch(context: CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
}
