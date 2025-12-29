package com.github.albertocavalcante.groovyparser.api

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit as CustomCompilationUnit

/**
 * Extensions for converting between native Groovy AST and the custom AST
 * from groovyparser-core.
 */

/**
 * Converts the native Groovy AST (ModuleNode) to the custom AST (CompilationUnit).
 *
 * This provides a JavaParser-like API for working with Groovy code while
 * leveraging the existing parser infrastructure.
 *
 * Example usage:
 * ```kotlin
 * val facade = GroovyParserFacade()
 * val result = facade.parse(request)
 * val compilationUnit = result.toCustomAst()
 *
 * compilationUnit?.types?.forEach { type ->
 *     println("Found type: ${type.name}")
 * }
 * ```
 *
 * @return the converted CompilationUnit, or null if the AST is not available
 */
fun ParseResult.toCustomAst(): CustomCompilationUnit? = ast?.let { GroovyParser.convertFromNative(it) }
