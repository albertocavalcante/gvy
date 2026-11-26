// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionPackageInputsWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `a dangling package entry cannot conceal a new sibling declaring a shadow`() {
        val files = PackageFiles(workspace)
        SpockInputWireSession(files.project).use { wire ->
            wire.open()
            val service = wire.service()
            val clean = wire.owned()
            files.assertCleanBinding(clean)
            wire.assertTable(service)
            wire.breakSourceBase()
            wire.assertTable(service)
            val prior = files.regularFiles()
            val broken = Files.readString(files.project.base)
            val added = Files.writeString(
                files.neighbor.resolveSibling("Other.groovy"),
                "package example\nclass Specification {}",
            )
            assertEquals(prior + setOf(added), files.regularFiles())
            assertTrue(Files.isSymbolicLink(files.dangling))
            assertFalse(Files.exists(files.dangling))
            assertEquals(broken, Files.readString(files.project.base))
            val offered = wire.tables(service)
            files.assertRepairedBinding(clean, added)
            assertTrue(offered.isEmpty(), "the clean verdict's package gained a real same-package Specification")
        }
    }

    @Test
    fun `a dangling package entry cannot conceal removal of a listed regular sibling`() {
        val files = PackageFiles(workspace)
        SpockInputWireSession(files.project).use { wire ->
            wire.open()
            files.assertCleanBinding(wire.owned())
            val service = wire.service()
            wire.assertTable(service)
            wire.breakSourceBase()
            wire.assertTable(service)
            val prior = files.regularFiles()
            assertTrue(files.neighbor in prior)
            Files.delete(files.neighbor)
            assertEquals(prior - setOf(files.neighbor), files.regularFiles())
            assertTrue(Files.isSymbolicLink(files.dangling))
            assertTrue(wire.tables(service).isEmpty(), "the remembered package membership lost a regular sibling")
        }
    }

    private class PackageFiles(workspace: Path) {
        val project = SpockTableWireProject(workspace, "example.BaseSpec")
        private val cleanBase = "package example\nimport spock.lang.*\n" +
            "abstract class BaseSpec extends Specification {}\n"
        // Package membership includes Java sources. This sibling is never an eager Groovy compilation input.
        val neighbor = Files.writeString(
            project.base.resolveSibling("Neighbor.java"),
            "package example; class Neighbor {}",
        )
        val dangling = Files.createSymbolicLink(project.base.resolveSibling("Dangling.groovy"), Path.of("absent"))

        init {
            Files.writeString(project.base, cleanBase)
            assertTrue(Files.isSymbolicLink(dangling))
            assertFalse(Files.exists(dangling))
            assertEquals(setOf(project.base, neighbor), regularFiles())
        }

        fun regularFiles(): Set<Path> = Files.list(project.base.parent).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().toSet()
        }

        fun assertCleanBinding(parsed: ParseResult) {
            assertFalse(parsed.outcome.isDegraded, "${parsed.outcome}")
            val module = assertNotNull(parsed.ast)
            val owner = module.classes.single { it.name == "TableSpec" }
            val base = owner.superClass.redirect()
            assertEquals("example.BaseSpec", base.name)
            assertTrue(base.isPrimaryClassNode)
            assertEquals("spock.lang.Specification", base.superClass.redirect().name)
            val status = SpockSourceContext.from(module, false).classes.single().status
            assertEquals(SpecificationStatus.SPECIFICATION, status)
        }

        // Only this independent clean control repairs the broken body and explicitly admits the new physical file.
        fun assertRepairedBinding(clean: ParseResult, added: Path) {
            Files.writeString(project.base, cleanBase)
            val inputs = assertNotNull(clean.classpathInputs)
            val request = ParseRequest(
                project.uri,
                project.source,
                classpath = inputs.classpath,
                sourceRoots = inputs.sourceRoots,
                workspaceSources = regularFiles().toList(),
                compilePhase = Phases.CANONICALIZATION,
            )
            val parsed = GroovyParserFacade().parse(request)
            parsed.compilationUnit.classLoader.use {
                assertFalse(parsed.outcome.isDegraded, "${parsed.outcome}")
                val module = assertNotNull(parsed.ast)
                val owner = module.classes.single { it.name == "TableSpec" }
                val shadow = owner.superClass.redirect().superClass.redirect()
                assertEquals("example.Specification", shadow.name)
                assertTrue(shadow.isPrimaryClassNode)
                assertTrue(Files.isSameFile(added, Path.of(assertNotNull(shadow.module).context.name)))
                val status = SpockSourceContext.from(module, false).classes.single().status
                assertEquals(SpecificationStatus.NOT_SPECIFICATION, status)
            }
        }
    }
}
