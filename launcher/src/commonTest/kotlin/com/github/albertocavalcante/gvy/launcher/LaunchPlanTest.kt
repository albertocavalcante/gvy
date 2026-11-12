package com.github.albertocavalcante.gvy.launcher

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchPlanTest {
    @Test
    fun `Windows selects java exe and preserves a home ending in backslash`() {
        assertEquals(
            listOf("C:\\Java Home\\bin\\java.exe", "-Xmx2g", "-jar", "gvy.jar"),
            javaCommand(emptyList(), "C:\\Java Home\\", "gvy.jar", windows = true),
        )
    }

    @Test
    fun `Windows quoting escapes empty arguments quotes and trailing backslashes`() {
        assertEquals("\"\"", quoteWindowsArgument(""))
        assertEquals("\"a b\"", quoteWindowsArgument("a b"))
        assertEquals("\"a\\\"b\"", quoteWindowsArgument("a\"b"))
        assertEquals("\"C:\\a b\\\\\"", quoteWindowsArgument("C:\\a b\\"))
        assertEquals("\"\\\\\\\"\"", quoteWindowsArgument("\\\""))
        assertEquals("\"λ-世界-😀\"", quoteWindowsArgument("λ-世界-😀"))
    }

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
