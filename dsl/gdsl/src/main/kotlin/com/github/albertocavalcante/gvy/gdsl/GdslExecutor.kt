// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gdsl

import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedAstParser
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseLimits
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseResult
import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Reads GDSL scripts and captures their contributions.
 *
 * GDSL (Groovy DSL) scripts define type contributions for IDE completion. They are untrusted input,
 * so they are never executed: the script is parsed without any compile phase running
 * ([UntrustedAstParser]: no AST transformation, `@Grab` or class loading) and its declarative calls
 * are interpreted from the AST ([GdslInterpreter]).
 *
 * Example usage:
 * ```kotlin
 * val executor = GdslExecutor()
 * val result = executor.executeAndCapture(gdslContent, "jenkins.gdsl")
 * if (result.success) {
 *     result.methods.forEach { println("Method: ${it.name}") }
 * }
 * ```
 */
class GdslExecutor {
    private val logger = KotlinLogging.logger {}

    /**
     * Reads a GDSL script and captures all contributions.
     *
     * Unsupported constructs are skipped and listed in [GdslParseResult.warnings]; a script that is too
     * large, too deeply nested or not valid Groovy yields [GdslParseResult.error].
     *
     * @param scriptContent The GDSL script content
     * @param scriptName Name for error reporting (default: "script.gdsl")
     */
    fun executeAndCapture(scriptContent: String, scriptName: String = "script.gdsl"): GdslParseResult {
        val result = read(scriptContent, scriptName)
        if (!result.success) {
            logger.warn { "Could not read GDSL script $scriptName: ${result.error}" }
        } else {
            result.warnings.forEach { warning -> logger.warn { "GDSL $scriptName: $warning" } }
            logger.info {
                "Read GDSL script $scriptName (${result.methods.size} methods, ${result.properties.size} properties)"
            }
        }
        return result
    }

    /**
     * Never throws for any input: one malformed file must not abort loading the others. Errors (out of
     * memory, linkage) still propagate.
     */
    @Suppress("TooGenericExceptionCaught") // Untrusted input: any parser or interpreter failure is a result
    private fun read(scriptContent: String, scriptName: String): GdslParseResult = try {
        when (val parsed = UntrustedAstParser.parse(scriptContent, scriptName, PARSE_LIMITS)) {
            is UntrustedParseResult.Rejected -> GdslParseResult.error(rejection(parsed))
            is UntrustedParseResult.Parsed -> GdslInterpreter().interpret(parsed.module)
        }
    } catch (e: Exception) {
        GdslParseResult.error("GDSL script could not be read: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun rejection(rejected: UntrustedParseResult.Rejected): String {
        val position = rejected.line?.let { line -> " at line $line" + (rejected.column?.let { ", column $it" } ?: "") }
        return "GDSL script rejected: ${rejected.reason}${position.orEmpty()}"
    }

    companion object {
        /**
         * Largest GDSL source read, in characters; loaders also refuse files larger than this many bytes
         * before reading them. A Jenkins `/pipeline-syntax/gdsl` dump is a few hundred KB.
         */
        const val MAX_SOURCE_SIZE = 4 * 1024 * 1024

        /**
         * A Jenkins `/pipeline-syntax/gdsl` dump has hundreds of `method(...)` statements and nests about six
         * brackets deep (`contributor { if { method(namedParams: [parameter(...)]) } }`).
         */
        private val PARSE_LIMITS =
            UntrustedParseLimits(maxChars = MAX_SOURCE_SIZE, maxNesting = 32, maxStatements = 100_000)
    }
}
