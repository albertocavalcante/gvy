// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.nativeBinaryReadScope
import groovy.lang.GroovyResourceLoader
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBinaryReadScopeTest {
    @Test
    fun `late compiler resolution cannot consult Groovy sources or the workspace locator during a metadata read`(
        @TempDir root: Path,
    ) {
        val name = "LatePeer"
        val peer = Files.writeString(root.resolve("Bundle.groovy"), "class LatePeer {}")
        val located = mutableListOf<String>()
        val request = ParseRequest(
            URI.create("file:///Client.groovy"),
            "class Client {}",
            parseMode = ParseMode.MINIMAL,
            compilePhase = Phases.CANONICALIZATION,
            workspaceClassLocator = { candidate ->
                located.add(candidate)
                if (candidate == name) listOf(peer) else emptyList()
            },
        )
        val parsed = GroovyParserFacade().parse(request)
        val unit = parsed.compilationUnit
        unit.classLoader.use { loader ->
            val scope = assertNotNull(loader.nativeBinaryReadScope())
            val probes = mutableListOf<String>()
            loader.resourceLoader = GroovyResourceLoader { candidate ->
                probes.add(candidate)
                null
            }
            located.clear()
            scope.enter().use {
                assertNull(unit.classNodeResolver.resolveName(name, unit))
                assertTrue(probes.isEmpty(), probes.toString())
                assertTrue(located.isEmpty(), located.toString())
            }
            val result = assertNotNull(unit.classNodeResolver.resolveName(name, unit))
            assertNotNull(result.sourceUnit)
            assertEquals(listOf(name), located)
            assertTrue(name in probes)
        }
    }
}
