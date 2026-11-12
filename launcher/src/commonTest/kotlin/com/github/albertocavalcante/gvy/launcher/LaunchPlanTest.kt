package com.github.albertocavalcante.gvy.launcher

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchPlanTest {
    @Test
    fun `no application arguments preserves the stdio LSP entry point`() {
        assertEquals(
            listOf("java", "-Xmx2g", "-jar", "/opt/gvy/gvy.jar"),
            javaCommand(emptyList(), null, "/opt/gvy/gvy.jar"),
        )
    }

    @Test
    fun `arguments remain separate and unchanged`() {
        val arguments = listOf("check", "", "a b", "groovy-λ-世界", "a\"b\\c", "$(touch sentinel);*")
        assertEquals(
            listOf("/Java Home/bin/java", "-Xmx2g", "-jar", "/my app/gvy.jar") + arguments,
            javaCommand(arguments, "/Java Home/", "/my app/gvy.jar"),
        )
    }
}
