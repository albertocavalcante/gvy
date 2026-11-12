// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The workspace class locator (#1425) attributes only the files it added as failing sources. A broken file Groovy
 * finds by its own name lookup is not the locator's: excluding it as located would cost a recompile.
 */
class WorkspaceClassLocatorTest {
    @TempDir
    lateinit var src: Path

    @Test
    fun `a broken source found by file name is not excluded as a located source`() {
        Files.writeString(src.resolve("Helper.groovy"), "class Helper {\n") // unbalanced: fails to parse
        val spec = src.resolve("TableSpec.groovy")
        val content = "class TableSpec extends Helper {}\n"
        Files.writeString(spec, content)

        // No workspace sources: only the locator could attribute Helper.groovy, and it never added it.
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = spec.toUri(),
                content = content,
                sourceRoots = listOf(src),
                parseMode = ParseMode.MINIMAL,
                workspaceClassLocator = { emptyList() },
            ),
        )

        assertEquals(emptyList(), result.outcome.excludedSources.map { it.source })
    }
}
