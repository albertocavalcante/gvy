// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class PluginFoundationTest : BasePlatformTestCase() {
    @Test
    fun `test plugin loads without registering a server provider`() {
        val pluginId = PluginId.getId("org.gvy.jetbrains")
        assertTrue("GVY plugin must be loaded", PluginManagerCore.isLoaded(pluginId))

        myFixture.configureByText("sample.groovy", "println 'hello'")
        val providers =
            ExtensionPointName
                .create<LspServerSupportProvider>(
                    "com.intellij.platform.lsp.serverSupportProvider",
                ).extensionList
        assertFalse(
            "The foundation must not register a server provider",
            providers.any { it.javaClass.name.startsWith("org.gvy.jetbrains.") },
        )
    }
}
