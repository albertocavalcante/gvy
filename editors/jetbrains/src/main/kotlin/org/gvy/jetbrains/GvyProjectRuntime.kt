// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class GvyProjectRuntime(
    private val project: Project,
) : Disposable {
    private val epoch = AtomicLong()

    @Volatile
    private var disposed = false
    val generation: Long get() = epoch.get()
    internal val isActive: Boolean get() = !disposed && !project.isDisposed

    private fun invalidateSession() {
        epoch.incrementAndGet()
        publishInvalidation()
    }

    private fun publishInvalidation() {
        if (isActive) {
            project.messageBus.syncPublisher(GvyLifecycleListener.TOPIC).sessionInvalidated()
        }
    }

    internal fun serverStopped(expectedGeneration: Long): Long? {
        if (!isActive) {
            return null
        }
        val next = expectedGeneration + 1
        if (!epoch.compareAndSet(expectedGeneration, next)) {
            return null
        }
        publishInvalidation()
        return next
    }

    fun restart() {
        invalidateSession()
        if (!isActive || project.isDefault) {
            return
        }
        val manager = LspServerManager.getInstance(project)
        if (GvyProjectSettings.getInstance(project).state.enabled && TrustedProjects.isProjectTrusted(project)) {
            manager.stopAndRestartIfNeeded(GvyServerSupportProvider::class.java)
        } else {
            manager.stopServers(GvyServerSupportProvider::class.java)
        }
    }

    internal fun trustRevoked() {
        invalidateSession()
        if (!isActive || project.isDefault) {
            return
        }
        LspServerManager.getInstance(project).stopServers(GvyServerSupportProvider::class.java)
    }

    override fun dispose() {
        disposed = true
        epoch.incrementAndGet()
    }
}

interface GvyLifecycleListener {
    fun sessionInvalidated()

    companion object {
        @JvmField
        val TOPIC = Topic.create("GVY session lifecycle", GvyLifecycleListener::class.java)
    }
}
