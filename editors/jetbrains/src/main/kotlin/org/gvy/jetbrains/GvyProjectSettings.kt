// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "GvyProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GvyProjectSettings : PersistentStateComponent<GvyProjectSettings.Options> {
    data class Options(
        var enabled: Boolean = false,
        var javaExecutable: String = "",
        var serverJar: String = "",
        var jvmArguments: String = "-Xmx1g",
        var serverConfiguration: String = "{}",
    )

    @Volatile
    private var options = Options()

    override fun getState(): Options = options.copy()

    override fun loadState(state: Options) {
        options = state.copy()
    }

    companion object {
        fun getInstance(project: Project): GvyProjectSettings = project.service()
    }
}
