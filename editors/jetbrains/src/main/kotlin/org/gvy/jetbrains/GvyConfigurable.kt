// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class GvyConfigurable(
    private val project: Project,
) : Configurable {
    private var panel: JPanel? = null
    private var enabled: JCheckBox? = null
    private var java: TextFieldWithBrowseButton? = null
    private var jar: TextFieldWithBrowseButton? = null
    private var arguments: JTextArea? = null
    private var configuration: JTextArea? = null

    override fun getDisplayName(): String = "GVY"

    override fun createComponent(): JComponent {
        val enabledField = JCheckBox("Enable GVY in this trusted project")
        val javaField = pathField()
        val jarField = pathField()
        val argumentsField = JTextArea(4, 50)
        val configurationField = JTextArea(8, 50)
        enabled = enabledField
        java = javaField
        jar = jarField
        arguments = argumentsField
        configuration = configurationField
        val form =
            FormBuilder
                .createFormBuilder()
                .addComponent(enabledField)
                .addLabeledComponent("Local Java executable:", javaField)
                .addLabeledComponent("Local GVY server JAR:", jarField)
                .addLabeledComponent("JVM options (one complete option per line):", JScrollPane(argumentsField))
                .addLabeledComponent(
                    "Server configuration (canonical groovy.* JSON keys):",
                    JScrollPane(configurationField),
                ).addComponent(JLabel("Local .groovy files only. GVY does not download a server automatically."))
                .addComponentFillVertically(JPanel(), 0)
                .panel
        panel = form
        reset()
        return form
    }

    private fun pathField(): TextFieldWithBrowseButton =
        TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
        }

    override fun isModified(): Boolean =
        panel != null && editedOptions() != GvyProjectSettings.getInstance(project).state

    override fun apply() {
        val options = editedOptions()
        if (options.enabled) {
            if (!TrustedProjects.isProjectTrusted(project)) {
                throw ConfigurationException("Trust this project in IntelliJ IDEA before enabling GVY.")
            }
            try {
                GvyLaunchConfiguration.from(options)
            } catch (failure: Exception) {
                throw ConfigurationException(
                    failure.message ?: "Check the Java path, server JAR, JVM options and JSON configuration.",
                )
            }
        }
        GvyProjectSettings.getInstance(project).loadState(options)
        project.getService(GvyProjectRuntime::class.java).restart()
    }

    private fun editedOptions(): GvyProjectSettings.Options =
        GvyProjectSettings.Options(
            enabled = enabled?.isSelected ?: false,
            javaExecutable = java?.text.orEmpty(),
            serverJar = jar?.text.orEmpty(),
            jvmArguments = arguments?.text.orEmpty(),
            serverConfiguration = configuration?.text.orEmpty(),
        )

    override fun reset() {
        val options = GvyProjectSettings.getInstance(project).state
        enabled?.isSelected = options.enabled
        java?.text = options.javaExecutable
        jar?.text = options.serverJar
        arguments?.text = options.jvmArguments
        configuration?.text = options.serverConfiguration
    }

    override fun disposeUIResources() {
        panel = null
        enabled = null
        java = null
        jar = null
        arguments = null
        configuration = null
    }
}
