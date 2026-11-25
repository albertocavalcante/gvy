// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The references of a class are its uses, never the uses of a type parameter spelled like it: the compiler gives a
 * class literal or call receiver of such a parameter a placeholder that redirects to the parameter's bound, which
 * may be the class itself (#2443 review).
 */
class TypeParameterReferencesTest {
    private val service = GroovyCompilationService()
    private val uri = URI.create("file:///refs/Sample.groovy")

    private fun referencesOfClassT(content: String): List<Pair<Int, Int>> = runBlocking {
        service.compile(uri, content)
        ReferenceProvider(service)
            .provideReferences(uri.toString(), Position(1, 6), includeDeclaration = true)
            .toList()
            .map { it.range.start.line to it.range.start.character }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    @Test
    fun `a class literal and a static call on a type parameter bounded by the class are not references to it`() {
        val content = "package pkg\nclass T {}\n" +
            "class Box<T extends pkg.T> { def k() { T.class }; def j() { T.foo() }; def <T> void m() { T.class } }\n"

        // FIXME(#2459): expected (2, 24) too, the qualified bound `pkg.T`, which the scan does not report.
        // The class literals of the parameter must not be reported either way.
        assertEquals(listOf(1 to 6), referencesOfClassT(content))
    }

    @Test
    fun `references requested on a type parameter constructor never resolve to its bound class`() = runBlocking {
        val content = "package pkg\nclass T {}\n" +
            "class Box<T extends pkg.T> { def m() { new T() } }\n" +
            "class Real { def m() { new T() } }\n"
        service.compile(uri, content)
        val position = Position(2, content.lines()[2].indexOf("new T") + "new ".length)

        assertEquals(
            emptyList<Location>(),
            ReferenceProvider(service).provideReferences(uri.toString(), position, includeDeclaration = true).toList(),
        )
    }
}
