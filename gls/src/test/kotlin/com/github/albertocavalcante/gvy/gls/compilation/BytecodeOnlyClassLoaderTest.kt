// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.services.JvmClasspathReflection
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import java.net.URI
import groovy.lang.GroovyResourceLoader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BytecodeOnlyClassLoaderTest {
    @Test
    fun `reflection and type solving use held bytecode without looking for missing scripts`() {
        val probes = mutableListOf<String>()
        val parsed = GroovyParserFacade().parse(ParseRequest(URI.create("file:///Probe.groovy"), "class Probe {}"))
        parsed.compilationUnit.classLoader.use { held ->
            held.resourceLoader = GroovyResourceLoader { name ->
                probes.add(name)
                null
            }
            val missing = "unavailable.OnlyInSource"
            assertFailsWith<ClassNotFoundException> { held.loadClass(missing) }
            assertEquals(listOf(missing), probes)
            probes.clear()

            val view = bytecodeOnlyClassLoader(held)
            val reflection = JvmClasspathReflection { view }
            val solver = ReflectionTypeSolver(jreOnly = false, classLoader = view)
            assertEquals(String::class.java, reflection.loadClass("java.lang.String"))
            assertTrue(solver.tryToSolveType("java.lang.String").isSolved)
            assertNull(reflection.loadClass(missing))
            assertFalse(solver.tryToSolveType(missing).isSolved)
            assertTrue(probes.isEmpty(), probes.toString())
        }
    }
}
