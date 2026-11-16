// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Reduces a `codenarc.properties` file to the entries gvy is willing to hand to CodeNarc.
 *
 * The file normally comes from an untrusted workspace and CodeNarc applies it to rule objects, where a
 * regular-expression property can be a catastrophic pattern (ReDoS, #1324). Only `RuleName.property` entries for
 * known CodeNarc rules are kept; regex-valued properties are dropped, and values must be a boolean, an integer
 * from 0 to [MAX_INT_VALUE], or a short wildcard-name list. Everything else is dropped and logged.
 */
internal object PropertiesSanitizer {
    const val MAX_FILE_BYTES = 64L * 1024
    const val MAX_INT_VALUE = 1_000_000
    private const val MAX_VALUE_LENGTH = 200

    private val KEY = Regex("([A-Za-z][A-Za-z0-9_]*)\\.([A-Za-z][A-Za-z0-9_]*)")
    private val INTEGER = Regex("[0-9]{1,7}")
    private val NAME_LIST = Regex("[A-Za-z0-9_.,*?$ -]{0,$MAX_VALUE_LENGTH}")

    data class Result(val content: String, val rejectedKeys: List<String>)

    /** Returns the sanitized properties text, or null when [file] is unreadable or too large. */
    fun sanitize(file: Path): Result? {
        val properties = Properties()
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                logger.warn { "Ignoring CodeNarc properties file larger than $MAX_FILE_BYTES bytes: $file" }
                return null
            }
            Files.newBufferedReader(file).use { properties.load(it) }
        } catch (e: java.io.IOException) {
            logger.warn { "Ignoring unreadable CodeNarc properties file $file: ${e.message}" }
            return null
        } catch (e: IllegalArgumentException) {
            logger.warn { "Ignoring malformed CodeNarc properties file $file: ${e.message}" }
            return null
        }

        val rejected = mutableListOf<String>()
        val content = buildString {
            for (name in properties.stringPropertyNames().sorted()) {
                val value = properties.getProperty(name).trim()
                if (isAllowed(name, value)) {
                    append(name).append('=').append(value).append('\n')
                } else {
                    rejected += name.take(MAX_VALUE_LENGTH)
                }
            }
        }
        if (rejected.isNotEmpty()) {
            logger.warn { "Ignored ${rejected.size} CodeNarc property entries from $file: $rejected" }
        }
        return Result(content, rejected)
    }

    private fun isAllowed(name: String, value: String): Boolean {
        val match = KEY.matchEntire(name) ?: return false
        val (rule, property) = match.destructured
        if (isRegexProperty(property)) return false
        if (runCatching { requireRule(rule) }.isFailure) return false
        return when {
            value == "true" || value == "false" -> true
            INTEGER.matches(value) -> value.toInt() <= MAX_INT_VALUE
            value.all { it.isDigit() } -> false
            else -> value.isNotEmpty() && NAME_LIST.matches(value)
        }
    }
}
