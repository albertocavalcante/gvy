// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class PluginFoundationTest : BasePlatformTestCase() {
    @Test
    fun `test plugin loads without starting a disabled server`() {
        val pluginId = PluginId.getId("org.gvy.jetbrains")
        assertTrue("GVY plugin must be loaded", PluginManagerCore.isLoaded(pluginId))

        myFixture.configureByText("sample.groovy", "println 'hello'")
        val providers =
            ExtensionPointName
                .create<LspServerSupportProvider>(
                    "com.intellij.platform.lsp.serverSupportProvider",
                ).extensionList
        assertEquals(
            "GVY must register exactly one provider",
            1,
            providers.count { it is GvyServerSupportProvider },
        )
        assertFalse(GvyProjectSettings.getInstance(project).state.enabled)
        val servers = LspServerManager.getInstance(project).getServersForProvider(GvyServerSupportProvider::class.java)
        assertTrue(servers.isEmpty())
        val descriptor = GvyServerDescriptor(project, GvyProjectSettings.getInstance(project).state)
        assertSame(LspFormattingDisabled, descriptor.lspCustomization.formattingCustomizer)
    }
}
