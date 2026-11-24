// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.tokens

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [GroovyTokenIndex.nameOccurrences] finds a name only where the lexer reads it as a name. */
class GroovyTokenIndexNameOccurrencesTest {
    private fun occurrences(source: String, name: String = "Service") =
        GroovyTokenIndex.build(source).nameOccurrences(source, name)

    /** The code spans of every whole-word [name] in [source] at or after each of the [needles]. */
    private fun code(source: String, vararg needles: String, name: String = "Service"): List<TokenSpan> =
        needles.map { needle ->
            val at = source.indexOf(needle).also { check(it >= 0) { "no '$needle' in $source" } } + needle.indexOf(name)
            TokenSpan(at, at + name.length, TokenContext.Code)
        }

    @Test
    fun `every whole identifier in code is found and longer or differently cased names are not`() {
        val source = "class Service { Service(Services s, service t) { } }\nService.m(); def x = new MyService()\n"

        assertEquals(code(source, "class Service", "{ Service(", "\nService.m"), occurrences(source))
    }

    @Test
    fun `strings, GString text, slashy strings and comments never match`() {
        val source = "def a = 'Service'\ndef b = \"Service \$x\"\ndef c = /Service/\ndef d = \$/Service/\$\n" +
            "def e = '''Service'''\n// Service\n/* Service */\n/** {@link Service} */\nclass K {}\n"

        assertEquals(emptyList(), occurrences(source))
    }

    @Test
    fun `a name inside a GString expression is code`() {
        val source = "def a = \"\${Service.NAME} and \$Service.NAME\"\n"

        assertEquals(code(source, "{Service", "\$Service"), occurrences(source))
    }

    @Test
    fun `a GString path segment that spells the name is reported after its dot`() {
        val source = "def a = \"\$x.Service and \$x.Services\"\n"
        val at = source.indexOf(".Service") + 1

        assertEquals(listOf(TokenSpan(at, at + "Service".length, TokenContext.GString)), occurrences(source))
    }

    @Test
    fun `spans count UTF-16 units for a name outside the basic plane`() {
        val name = "𝐀Svc"
        val source = "/* 😀 */ class $name { }\r\ndef s = new $name()\r\n"

        assertEquals(code(source, "class $name", "new $name", name = name), occurrences(source, name))
    }

    @Test
    fun `an incomplete lexer pass answers nothing`() {
        assertNull(occurrences("class Service { def s = 'unterminated\n}\n"))
    }

    @Test
    fun `an empty name answers nothing`() {
        assertNull(occurrences("class Service { }", name = ""))
    }
}
