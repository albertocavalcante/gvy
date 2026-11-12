// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.AnalysisCompilerPolicy
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovySystem
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ParserPlugin
import org.codehaus.groovy.control.ParserPluginFactory
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.ProcessingUnit
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.Reduction
import org.codehaus.groovy.syntax.SyntaxException
import org.junit.jupiter.api.Test
import java.io.Reader
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Calibrates compiler output and completion witnesses before a fresh-parse benchmark can use them. */
class GroovyCompilerPhaseCalibrationTest {

    @Test
    fun `conversion builds the expected AST and completes the source after its progress callback`() {
        withCompiler(ALPHA_SOURCE) { unit, source ->
            var callbackWitness: CompilerWitness? = null
            unit.progressCallback = CompilationUnit.ProgressCallback { context, phase ->
                if (context === unit && phase == Phases.CONVERSION) {
                    callbackWitness = witness(unit, source)
                }
            }

            unit.compile(Phases.CONVERSION)

            assertEquals(
                CompilerWitness(PhaseState(Phases.CONVERSION, false), PhaseState(Phases.CONVERSION, false)),
                callbackWitness,
            )
            assertEquals(
                CompilerWitness(PhaseState(Phases.SEMANTIC_ANALYSIS, false), PhaseState(Phases.CONVERSION, true)),
                witness(unit, source),
            )
            assertEquals(listOf(source), unit.iterator().asSequence().toList())
            assertSame(source.ast, unit.ast.modules.single())
            assertFalse(unit.errorCollector.hasErrors())
            assertFixtureAst(source.ast, "Sample", "alpha", 17)
        }
    }

    @Test
    fun `a completed parsing phase does not satisfy the useful conversion AST oracle`() {
        withCompiler(ALPHA_SOURCE) { unit, source ->
            unit.compile(Phases.PARSING)

            assertEquals(
                CompilerWitness(PhaseState(Phases.CONVERSION, false), PhaseState(Phases.PARSING, true)),
                witness(unit, source),
            )
            assertFalse(unit.errorCollector.hasErrors())
            assertNull(source.ast)
            assertFailsWith<AssertionError> { assertFixtureAst(source.ast, "Sample", "alpha", 17) }
        }
    }

    @Test
    fun `real malformed ANTLR input fails before producing a useful conversion AST`() {
        withCompiler("class Sample { int alpha( { }") { unit, source ->
            assertFailsWith<CompilationFailedException> { unit.compile(Phases.CONVERSION) }

            assertEquals(
                CompilerWitness(PhaseState(Phases.CONVERSION, false), PhaseState(Phases.PARSING, true)),
                witness(unit, source),
            )
            assertTrue(unit.errorCollector.hasErrors())
            assertNull(source.ast)
            assertFailsWith<AssertionError> { assertFixtureAst(source.ast, "Sample", "alpha", 17) }
        }
    }

    @Test
    fun `an injected parser SyntaxException leaves an incomplete nonqualifying placeholder module`() {
        withCompiler(
            ALPHA_SOURCE,
            configure = { configuration ->
                val stockParser = configuration.pluginFactory.createParserPlugin()
                configuration.pluginFactory = AstConstructionFailureFactory(stockParser)
            },
        ) { unit, source ->
            assertFailsWith<CompilationFailedException> { unit.compile(Phases.CONVERSION) }

            assertEquals(
                CompilerWitness(PhaseState(Phases.CONVERSION, false), PhaseState(Phases.CONVERSION, false)),
                witness(unit, source),
            )
            val error = assertIs<SyntaxErrorMessage>(unit.errorCollector.errors.single())
            assertEquals(INJECTED_FAILURE, error.cause.originalMessage)
            assertNotNull(source.ast, "SourceUnit retains a placeholder for this injected SyntaxException")
            assertFailsWith<AssertionError> { assertFixtureAst(source.ast, "Sample", "alpha", 17) }
        }
    }

    @Test
    fun `the trusted failing transform emits conversion failed and cannot qualify via facade phase labels`() {
        assertRuntimeProfile(CompilerConfiguration())
        // Existing test SPI throws CompilationFailedException only for this exact class name.
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = URI.create("file:///FailsWithoutErrors.groovy"),
                content = "class FailsWithoutErrors { int alpha() { return 17 } }",
                compilePhase = Phases.CONVERSION,
                parseMode = ParseMode.MINIMAL,
            ),
        )

        try {
            assertFixtureAst(result.ast, "FailsWithoutErrors", "alpha", 17)
            assertEquals(Phases.CONVERSION, result.outcome.requestedPhase)
            assertEquals(Phases.CONVERSION, result.outcome.achievedPhase)
            assertEquals(
                CompilerWitness(PhaseState(Phases.CONVERSION, false), PhaseState(Phases.CONVERSION, false)),
                witness(result.compilationUnit, result.sourceUnit),
            )
            assertFalse(result.compilationUnit.errorCollector.hasErrors())
            val failure = result.diagnostics.single()
            assertEquals(ParserSeverity.ERROR, failure.severity)
            assertEquals("GroovyParser", failure.source)
            assertEquals("Compilation failed: conversion failed", failure.message)
            assertFalse(result.isSuccessful)
        } finally {
            // This per-parse project loader is distinct from the facade's shared transform loader.
            result.compilationUnit.classLoader.close()
        }
    }

    @Test
    fun `same length inputs at the same source name build fresh independently expected ASTs`() {
        assertEquals(ALPHA_SOURCE.length, OMEGA_SOURCE.length)
        assertNotEquals(ALPHA_SOURCE, OMEGA_SOURCE)
        val observedModules = mutableListOf<ModuleNode>()
        val inputs = listOf(
            Triple(ALPHA_SOURCE, "alpha", 17),
            Triple(OMEGA_SOURCE, "omega", 29),
            Triple(ALPHA_SOURCE, "alpha", 17),
            Triple(OMEGA_SOURCE, "omega", 29),
        )

        for ((text, method, value) in inputs) {
            withCompiler(text) { unit, source ->
                unit.compile(Phases.CONVERSION)

                assertFalse(unit.errorCollector.hasErrors())
                assertEquals(PhaseState(Phases.CONVERSION, true), phaseState(source))
                assertFixtureAst(source.ast, "Sample", method, value)
                val module = assertNotNull(source.ast)
                for (previous in observedModules) {
                    assertNotSame(previous, module)
                }
                observedModules.add(module)
            }
        }
    }

    private fun withCompiler(
        text: String,
        configure: (CompilerConfiguration) -> Unit = {},
        verify: (CompilationUnit, SourceUnit) -> Unit,
    ) {
        val configuration = CompilerConfiguration()
        assertRuntimeProfile(configuration)
        AnalysisCompilerPolicy.apply(configuration)
        configure(configuration)

        GroovyClassLoader(javaClass.classLoader, configuration).use { loader ->
            val unit = CompilationUnit(configuration, null, loader, loader)
            val source = SourceUnit("Calibration.groovy", text, configuration, loader, unit.errorCollector)
            unit.addSource(source)
            try {
                verify(unit, source)
            } finally {
                unit.progressCallback = null
            }
        }
    }

    private fun assertRuntimeProfile(configuration: CompilerConfiguration) {
        assertEquals("4.0.33", GroovySystem.getVersion())
        assertNotEquals("xml", System.getProperty("groovy.ast"), "Calibration must not serialize an AST to disk")
        assertEquals(
            "org.apache.groovy.parser.antlr4.Antlr4ParserPlugin",
            configuration.pluginFactory.createParserPlugin().javaClass.name,
        )
    }

    private fun assertFixtureAst(module: ModuleNode?, className: String, methodName: String, value: Int) {
        assertNotNull(module)
        assertEquals(listOf(className), module.classes.map { it.name })
        val clazz = module.classes.single()
        assertEquals(listOf(methodName), clazz.methods.map { it.name })
        val method = clazz.methods.single()
        assertEquals("int", method.returnType.name)
        assertTrue(method.parameters.isEmpty())
        val block = assertIs<BlockStatement>(method.code)
        val returned = assertIs<ReturnStatement>(block.statements.single())
        assertEquals(value, assertIs<ConstantExpression>(returned.expression).value)
    }

    private fun witness(unit: CompilationUnit, source: SourceUnit) =
        CompilerWitness(phaseState(unit), phaseState(source))

    private fun phaseState(unit: ProcessingUnit) = PhaseState(unit.phase, unit.isPhaseComplete)

    private data class PhaseState(val phase: Int, val complete: Boolean)

    private data class CompilerWitness(val unit: PhaseState, val source: PhaseState)

    /** Local configuration only: preserve stock parseCST and fail exactly at the AST-construction boundary. */
    private class AstConstructionFailureFactory(private val stockParser: ParserPlugin) : ParserPluginFactory() {
        override fun createParserPlugin(): ParserPlugin = object : ParserPlugin {
            override fun parseCST(sourceUnit: SourceUnit, reader: Reader): Reduction? =
                stockParser.parseCST(sourceUnit, reader)

            override fun buildAST(sourceUnit: SourceUnit, classLoader: ClassLoader, cst: Reduction?): ModuleNode =
                throw SyntaxException(INJECTED_FAILURE, 1, 1)
        }
    }

    private companion object {
        const val INJECTED_FAILURE = "calibration injected AST construction failure"
        const val ALPHA_SOURCE = "class Sample { int alpha() { return 17 } }"
        const val OMEGA_SOURCE = "class Sample { int omega() { return 29 } }"
    }
}
