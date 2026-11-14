// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileEntryPointsTest {
    @Test
    fun `script with top level methods and static main is runnable as a script`() {
        val entries =
            discoverEntryPoints(
                module(
                    """
            def greet(name) { println name }
            static void main(String[] args) { println args }
            greet('world')
                    """.trimIndent(),
                ),
            )
        assertEquals(listOf("script"), entries.map { it.id })
    }

    @Test
    fun `finds compatible public static mains and ignores other methods`() {
        val entries =
            discoverEntryPoints(
                module(
                    """
            package demo
            class First { static void main(String[] args) {} }
            class Second { public static void main(String... args) {} }
            class Wrong { void main(String[] args) {} }
            class Other { static void main(int value) {} }
                    """.trimIndent(),
                ),
            )
        assertEquals(listOf("demo.First", "demo.Second"), entries.map { it.id })
    }

    @Test
    fun `a local String class does not make an incompatible main runnable`() {
        val source = "class String {}\nclass Entry { static void main(String[] args) {} }"
        assertEquals(emptyList<String>(), discoverEntryPoints(module(source)).map { it.id })
    }

    @Test
    fun `ordinary class has no runnable entry`() {
        assertEquals(emptyList<String>(), discoverEntryPoints(module("class Plain {} ")).map { it.id })
    }

    private fun module(source: String): ModuleNode = SourceUnit.create("Example.groovy", source).run {
        parse()
        completePhase()
        convert()
        ast
    }
}
