package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.slf4j.LoggerFactory
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Lightweight, LSP-free parser facade that produces Groovy ASTs and diagnostics.
 */
class GroovyParserFacade(private val parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader()) {

    private val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java)

    /**
     * Parse a Groovy source file.
     *
     * If compilation fails at a phase later than CONVERSION and produces a "Script fallback"
     * (where the class extends groovy.lang.Script due to unresolved superclass), this method
     * automatically retries at CONVERSION phase to preserve the original class structure.
     */
    fun parse(request: ParseRequest): ParseResult {
        val result = parseInternal(request)

        // Check if we should retry at an earlier phase
        if (shouldRetryAtConversion(request, result)) {
            logger.info("Detected Script fallback for ${request.uri}, retrying at CONVERSION phase")
            return parseInternal(request.copy(compilePhase = org.codehaus.groovy.control.Phases.CONVERSION))
        }

        return result
    }

    /**
     * Determines if we should retry parsing at CONVERSION phase.
     *
     * This handles the case where Groovy's compiler converts a class to a Script when
     * it cannot resolve the superclass (e.g., `extends spock.lang.Specification` when
     * Spock is not on the classpath).
     */
    private fun shouldRetryAtConversion(request: ParseRequest, result: ParseResult): Boolean {
        // Only retry if we compiled past CONVERSION phase
        if (request.compilePhase <= org.codehaus.groovy.control.Phases.CONVERSION) {
            logger.debug(
                "shouldRetryAtConversion: false - already at CONVERSION or earlier (phase=${request.compilePhase})",
            )
            return false
        }

        // NOTE: We do NOT check result.isSuccessful here.
        // Groovy often successfully compiles a broken class as a Script, so we must check the AST structure
        // regardless of compilation status.

        // Check for Script fallback pattern: single class extending groovy.lang.Script
        val ast = result.ast
        if (ast == null) {
            logger.debug("shouldRetryAtConversion: false - AST is null")
            return false
        }

        val classes = ast.classes
        if (classes.isEmpty()) {
            logger.debug("shouldRetryAtConversion: false - no classes in AST")
            return false
        }

        // Log class details for debugging - use safe call for superClass (null for interfaces)
        val classInfo = classes.map { "${it.name} (super=${it.superClass?.name ?: "null"})" }
        logger.info("shouldRetryAtConversion: checking classes: $classInfo")

        if (classes.size != 1) {
            logger.debug("shouldRetryAtConversion: false - ${classes.size} classes (expected 1)")
            return false
        }

        val cls = classes[0]
        // Use safe call for superClass - it's null for interfaces
        val isScript = cls.superClass?.name == "groovy.lang.Script"
        if (!isScript) {
            logger.info("shouldRetryAtConversion: false - not a Script for ${cls.name}")
            return false
        }

        // Check if the source code actually contains a class declaration.
        // If it doesn't, this is an intentional script (not a class that got converted to Script).
        // We only want to retry for classes that got incorrectly converted to Script due to
        // unresolved superclass (e.g., "extends spock.lang.Specification" when Spock isn't on classpath).
        val hasClassKeyword = request.content.contains(Regex("""\bclass\s+\w+"""))
        if (!hasClassKeyword) {
            logger.debug("shouldRetryAtConversion: false - intentional script (no class keyword)")
            return false
        }

        logger.info("shouldRetryAtConversion: true - class ${cls.name} was converted to Script")
        return true
    }

    private fun parseInternal(request: ParseRequest): ParseResult {
        val config = createCompilerConfiguration()
        // Use configured parent classloader (default is platform/bootstrap to avoid pollution)
        val classLoader = GroovyClassLoader(parentClassLoader)

        request.classpath.forEach { classLoader.addClasspath(it.toString()) }
        request.sourceRoots.forEach { classLoader.addClasspath(it.toString()) }

        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(request.content, config)
        val sourceUnit = SourceUnit(request.sourceUnitName, source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        addWorkspaceSources(compilationUnit, request)

        var compilationFailed = false
        try {
            compilationUnit.compile(request.compilePhase)
        } catch (e: CompilationFailedException) {
            compilationFailed = true
            logger.debug("Compilation failed for ${request.uri}: ${e.message}")
        }

        val tokenIndex = GroovyTokenIndex.build(request.content)

        val ast = extractAst(compilationUnit)
        val diagnostics =
            ParserDiagnosticConverter.convert(compilationUnit.errorCollector, request.locatorCandidates).toMutableList()

        if (compilationFailed && diagnostics.none { it.severity == ParserSeverity.ERROR }) {
            val errorMessage = compilationUnit.errorCollector.lastError?.toString() ?: "Unknown error"
            diagnostics.add(
                com.github.albertocavalcante.groovyparser.api.ParserDiagnostic(
                    range = com.github.albertocavalcante.groovyparser.api.ParserRange.point(0, 0),
                    severity = ParserSeverity.ERROR,
                    message = "Compilation failed: $errorMessage",
                    source = "GroovyParser",
                ),
            )
        }

        val tracker = NodeRelationshipTracker()
        val astModel = RecursiveAstVisitor(tracker)
        ast?.let { astModel.visitModule(it, request.uri) }

        val symbolTable = SymbolTable()
        symbolTable.buildFromVisitor(astModel)

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
            astModel = astModel,
            tokenIndex = tokenIndex,
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
                    logger.info("Adding workspace source: $path")
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
