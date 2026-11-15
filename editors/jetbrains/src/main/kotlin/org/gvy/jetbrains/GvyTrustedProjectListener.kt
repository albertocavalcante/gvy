// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.project.Project

/** Registered with the platform so cold untrusted projects do not depend on LSP file callbacks. */
class GvyTrustedProjectListener : TrustedProjectsListener {
    override fun onProjectTrusted(project: Project) {
        if (!project.isDisposed && !project.isDefault && GvyProjectSettings.getInstance(project).state.enabled) {
            project.getService(GvyProjectRuntime::class.java).restart()
        }
    }

    override fun onProjectUntrusted(project: Project) {
        if (!project.isDisposed && !project.isDefault) {
            project.getService(GvyProjectRuntime::class.java).trustRevoked()
        }
    }
}
