package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.AstVisitor
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.slf4j.LoggerFactory
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Lightweight, LSP-free parser facade that produces Groovy ASTs and diagnostics.
 */
class GroovyParserFacade {

    private val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java)

    fun parse(request: ParseRequest): ParseResult {
        val config = createCompilerConfiguration()
        val classLoader = GroovyClassLoader()

        request.classpath.forEach { classLoader.addClasspath(it.toString()) }
        request.sourceRoots.forEach { classLoader.addClasspath(it.toString()) }

        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(request.content, config)
        val sourceUnit = SourceUnit(request.sourceUnitName, source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        addWorkspaceSources(compilationUnit, request)

        try {
            compilationUnit.compile(Phases.CANONICALIZATION)
        } catch (e: CompilationFailedException) {
            logger.debug("Compilation failed for ${request.uri}: ${e.message}")
        }

        val ast = extractAst(compilationUnit)
        val diagnostics = ParserDiagnosticConverter.convert(compilationUnit.errorCollector, request.locatorCandidates)

        val recursiveVisitor = if (request.useRecursiveVisitor && ast != null) {
            val tracker = NodeRelationshipTracker()
            val visitor = RecursiveAstVisitor(tracker)
            visitor.visitModule(ast, request.uri)
            visitor
        } else {
            null
        }

        val astVisitor = AstVisitor()
        ast?.let { astVisitor.visitModule(it, sourceUnit, request.uri) }
        val symbolTable = SymbolTable()
        symbolTable.buildFromVisitor(astVisitor)

        logger.debug(
            "Parsed {} -> success={}, diagnostics={}",
            request.uri,
            ast != null && diagnostics.none { it.severity == ParserSeverity.ERROR },
            diagnostics.size,
        )

        return ParseResult(
            ast = ast,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = diagnostics,
            symbolTable = symbolTable,
            astVisitor = astVisitor,
            recursiveVisitor = recursiveVisitor,
        )
    }

    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        targetDirectory = null
        debug = true
        optimizationOptions = mapOf(
            CompilerConfiguration.GROOVYDOC to true,
        )
        sourceEncoding = "UTF-8"
    }

    private fun addWorkspaceSources(compilationUnit: CompilationUnit, request: ParseRequest) {
        request.workspaceSources
            .filter {
                it.toUri() != request.uri &&
                    it.extension.equals("groovy", ignoreCase = true) &&
                    it.isRegularFile()
            }
            .forEach { path ->
                runCatching {
                    compilationUnit.addSource(path.toFile())
                }.onFailure { throwable ->
                    logger.debug("Failed adding workspace source {}: {}", path, throwable.message)
                }
            }
    }

    private fun extractAst(compilationUnit: CompilationUnit): ModuleNode? = try {
        val ast = compilationUnit.ast
        if (ast?.modules?.isNotEmpty() == true) {
            ast.modules.first()
        } else {
            logger.debug("No modules available in compilation unit")
            null
        }
    } catch (e: CompilationFailedException) {
        logger.debug("Failed to extract AST: ${e.message}")
        null
    }
}
