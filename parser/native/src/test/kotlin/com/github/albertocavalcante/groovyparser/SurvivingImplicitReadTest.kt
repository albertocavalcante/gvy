// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeRetryInputs
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Use the public native request's unfiltered diagnostics so a foreign syntax error survives phase recovery. */
class SurvivingImplicitReadTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `surviving implicit disk reader that never produced a module retains its content`(replace: Boolean) {
        val uri = root.resolve("Primary.groovy").toUri()
        val path = root.resolve("Broken.groovy")
        val broken = "class Broken { void m( }"
        val fixed = "class Broken {}".padEnd(broken.length)
        val primary = "class Primary { Broken member }"
        val request = ParseRequest(uri, primary, sourceRoots = listOf(root), compilePhase = Phases.CANONICALIZATION)
        Files.writeString(path, broken)
        val parsed = GroovyParserFacade().parse(request)
        parsed.compilationUnit.classLoader.use {
            val errors = parsed.compilationUnit.errorCollector.errors.orEmpty().filterIsInstance<SyntaxErrorMessage>()
            assertTrue(errors.any { message ->
                val locator = message.cause.sourceLocator ?: return@any false
                val errorPath = if (locator.startsWith("file:")) Path.of(URI(locator)) else Path.of(locator)
                errorPath == path
            }, "Setup requires the final unit's syntax error in the physical Broken.groovy")
            assertTrue(parsed.diagnostics.any {
                it.severity == ParserSeverity.ERROR && !it.message.startsWith("Compilation failed:")
            })
            val modules = parsed.compilationUnit.ast.modules
            val owner = modules.single { it.context === parsed.sourceUnit }
            assertSame(owner, parsed.ast)
            assertTrue(modules.none { CompiledDocumentInput.of(it)?.uri == path.toUri() })
            val failedUnit = parsed.compilationUnit.iterator().asSequence()
                .single { it.name == path.toUri().toURL().toExternalForm() }
            assertTrue(modules.none { it.context === failedUnit }, "Failure must precede module installation")
            val history = assertNotNull(NativeRetryInputs.of(parsed))
            assertFalse(history.hasDiscarded, "An earlier-attempt result cannot exercise this boundary")
            assertTrue(parsed.outcome.fallback == null && parsed.outcome.stubbedClasses.isEmpty())
            val membership = assertNotNull(NativeSourceMembership.of(owner))
            assertEquals(NativeSourceMembership.Status.CURRENT, membership.check {})
            if (replace) {
                val stamp = Files.getLastModifiedTime(path)
                Files.writeString(path, fixed)
                Files.setLastModifiedTime(path, stamp)
                assertEquals(stamp, Files.getLastModifiedTime(path))
                val alternate = GroovyParserFacade().parse(request)
                alternate.compilationUnit.classLoader.use {
                    assertTrue(alternate.isSuccessful)
                    val module = alternate.compilationUnit.ast.modules.single {
                        CompiledDocumentInput.of(it)?.uri == path.toUri()
                    }
                    val read = assertNotNull(CompiledDocumentInput.of(module))
                    assertTrue(read.matchesText(fixed) && read.matchesCurrentText(null) {})
                    val field = assertNotNull(alternate.ast).classes.single().getDeclaredField("member")
                    assertSame(module.classes.single(), field.type.redirect())
                }
            }
            assertEquals(NativeSourceMembership.Status.CURRENT, membership.check {}, "Selection alone still matches")
            val expected = if (replace) NativeSourceMembership.Status.CHANGED else NativeSourceMembership.Status.CURRENT
            assertEquals(expected, history.check(emptySet(), { if (it == uri) primary else null }) {})
        }
    }
}
