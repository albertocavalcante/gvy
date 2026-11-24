// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The constructor name locator refuses whenever the compiler's facts and the text do not agree exactly. */
class ConstructorNameReaderTest {
    @TempDir
    lateinit var workspace: Path

    private val rename by lazy { SingleFileRename(workspace) }
    private val compiled = "class Service {\n    Service(int a) { }\n}\n"

    private fun service() = runBlocking {
        rename.open(compiled)
        rename.parsedClass("Service")
    }

    @Test
    fun `the name is located in the text the class was compiled from`() {
        val tokens = GroovyTokenIndex.build(compiled)

        val located = ConstructorNameReader.locate(service(), "Service", TextIndex(compiled), tokens)

        assertEquals(ConstructorNames.Located(listOf(Range(Position(1, 4), Position(1, 11)))), located)
    }

    @Test
    fun `a header that does not spell the name where the compiler puts it is unlocatable`() {
        val other = "class Service {\n    Serv1ce(int a) { }\n}\n"

        val tokens = GroovyTokenIndex.build(other)

        val located = ConstructorNameReader.locate(service(), "Service", TextIndex(other), tokens)

        assertEquals(ConstructorNames.Unlocatable(2), located)
    }

    @Test
    fun `without a lexer pass every constructor is unlocatable`() {
        assertEquals(
            ConstructorNames.Unlocatable(2),
            ConstructorNameReader.locate(service(), "Service", TextIndex(compiled), null),
        )
    }

    /**
     * No source the compiler accepts was found whose constructor name the reader cannot locate; the tests above reach
     * that refusal through the reader. Headers that would (`var Service()`, an escaped `Serv\u0069ce(...)`) are not
     * constructors Groovy accepts, so the rename refuses before locating anything.
     */
    @Test
    fun `a var header is not a constructor Groovy accepts and the rename refuses without editing`() {
        val content = "class Service {\n    var Service() { }\n}\n"

        val failure = runBlocking { rename.apply(content, Position(0, 7), "Worker") }.exceptionOrNull()

        val refused = assertInstanceOf(ResponseErrorException::class.java, failure)
        assertEquals("Document not compiled: ${rename.uri}", refused.message)
    }
}
