// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import org.eclipse.lsp4j.ConfigurationItem

class GvyServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (project.isDisposed || project.isDefault) {
            return
        }
        val options = GvyProjectSettings.getInstance(project).state
        if (options.enabled) {
            // Create project lifecycle state without starting a server.
            project.getService(GvyProjectRuntime::class.java)
        }
        val eligible =
            canStartGvy(
                options.enabled,
                TrustedProjects.isProjectTrusted(project),
                file.isInLocalFileSystem,
                file.extension,
            )
        if (!eligible) {
            return
        }
        serverStarter.ensureServerStarted(GvyServerDescriptor(project, options))
    }
}

internal class GvyServerDescriptor(
    private val gvyProject: Project,
    private val options: GvyProjectSettings.Options,
) : ProjectWideLspServerDescriptor(gvyProject, "GVY") {
    private val runtime = gvyProject.getService(GvyProjectRuntime::class.java)
    private val generation = runtime.generation

    override val lspCustomization =
        object : LspCustomization() {
            override val formattingCustomizer: LspFormattingCustomizer = LspFormattingDisabled
        }

    override val lspServerListener =
        object : LspServerListener {
            override fun serverStopped(shutdownNormally: Boolean) {
                runtime.serverStopped(generation)
            }
        }

    override fun isSupportedFile(file: VirtualFile): Boolean =
        runtime.isActive &&
            canStartGvy(
                GvyProjectSettings.getInstance(gvyProject).state.enabled,
                TrustedProjects.isProjectTrusted(gvyProject),
                file.isInLocalFileSystem,
                file.extension,
            )

    override fun createCommandLine(): GeneralCommandLine {
        if (!runtime.isActive) {
            throw ExecutionException("GVY project services are disposed.")
        }
        val currentOptions = GvyProjectSettings.getInstance(gvyProject).state
        val currentGeneration = runtime.generation
        if (!runtime.isActive || currentOptions != options || !currentOptions.enabled ||
            currentGeneration != generation || !TrustedProjects.isProjectTrusted(gvyProject)
        ) {
            throw ExecutionException("Enable GVY in a trusted local project before starting its server.")
        }
        return try {
            val configuration = GvyLaunchConfiguration.from(options)
            GeneralCommandLine(configuration.command).withWorkDirectory(gvyProject.basePath).withCharset(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw ExecutionException("Check Settings > Languages & Frameworks > GVY: ${failure.message}", failure)
        }
    }

    override fun createInitializationOptions(): Any = GvyLaunchConfiguration.from(options).initializationOptions

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? {
        val configuration = GvyLaunchConfiguration.from(options).initializationOptions
        return configuration[item.section] ?: super.getWorkspaceConfiguration(item)
    }
}
