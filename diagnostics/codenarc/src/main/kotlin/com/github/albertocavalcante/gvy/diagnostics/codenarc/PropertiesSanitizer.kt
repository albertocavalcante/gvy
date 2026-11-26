// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import groovy.lang.GroovySystem
import groovy.lang.MetaBeanProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Reduces a `codenarc.properties` file to the entries gvy is willing to hand to CodeNarc.
 *
 * The file normally comes from an untrusted workspace and CodeNarc applies it to rule objects, where a
 * regular-expression property can be a catastrophic pattern (ReDoS, #1324). Only `RuleName.property` entries for
 * known CodeNarc rules are kept, and the property must have a setter on the rule class whose type matches the value:
 * boolean, integer from 0 to [MAX_INT_VALUE], or a plain name list without wildcards (CodeNarc turns `*` and `?`
 * into backtracking regex quantifiers, so wildcards are never accepted). Regex-valued property names are dropped.
 * Everything else is dropped and logged. Symlinks and non-regular files are not read.
 */
internal object PropertiesSanitizer {
    const val MAX_FILE_BYTES = 64L * 1024
    const val MAX_INT_VALUE = 1_000_000
    private const val MAX_VALUE_LENGTH = 200

    private val KEY = Regex("([A-Za-z][A-Za-z0-9_]*)\\.([A-Za-z][A-Za-z0-9_]*)")
    private val INTEGER = Regex("[0-9]{1,7}")
    private val NAME_LIST = Regex("[A-Za-z_][A-Za-z0-9_.,$ ]{0,${MAX_VALUE_LENGTH - 1}}")

    data class Result(val content: String, val rejectedKeys: List<String>)

    /** Returns the sanitized properties text, or null when [file] is unreadable, malformed or too large. */
    fun sanitize(file: Path): Result? = sanitize(file, null)

    fun sanitize(file: Path, observeBytes: ((ByteArray) -> Unit)?): Result? {
        val properties = load(file, observeBytes) ?: return null
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

    /** Reads at most [MAX_FILE_BYTES] from a regular, non-symlink file; null (logged) otherwise. */
    private fun load(file: Path, observeBytes: ((ByteArray) -> Unit)?): Properties? {
        val problem = try {
            val bytes = readBounded(file, strictIo = observeBytes != null)
            if (bytes != null) {
                observeBytes?.invoke(bytes)
                return Properties().apply { load(bytes.toString(Charsets.UTF_8).reader()) }
            }
            "is not a regular file or is larger than $MAX_FILE_BYTES bytes"
        } catch (_: NoSuchFileException) {
            "does not exist"
        } catch (e: java.io.IOException) {
            if (observeBytes != null) throw e
            "is unreadable: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "is malformed: ${e.message}"
        }
        logger.warn { "Ignoring CodeNarc properties file $file: it $problem" }
        return null
    }

    private fun readBounded(file: Path, strictIo: Boolean): ByteArray? {
        val regular = if (strictIo) {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile
        } else {
            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        }
        if (!regular) return null
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES.toInt() + 1) }
        return bytes.takeIf { it.size <= MAX_FILE_BYTES }
    }

    private fun isAllowed(name: String, value: String): Boolean {
        val match = KEY.matchEntire(name)
        val (rule, property) = match?.destructured ?: return false
        val type = if (isRegexProperty(property)) null else settablePropertyType(rule, property)
        return when (type) {
            // Only primitives: CodeNarc converts strings for int/long/boolean and would mis-assign boxed types.
            Boolean::class.javaPrimitiveType -> value == "true" || value == "false"
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType ->
                INTEGER.matches(value) && value.toInt() <= MAX_INT_VALUE
            String::class.java -> NAME_LIST.matches(value)
            else -> false
        }
    }

    /**
     * The property type as CodeNarc's `PropertyUtil` sees it (Groovy metaclass), or null when the property is missing
     * or has no setter. Groovy-generated and hand-written setters both show up here.
     */
    private fun settablePropertyType(rule: String, property: String): Class<*>? {
        val ruleClass = runCatching { Class.forName(requireRule(rule)) }.getOrNull() ?: return null
        val meta = GroovySystem.getMetaClassRegistry().getMetaClass(ruleClass)
        val metaProperty = meta.getMetaProperty(property) as? MetaBeanProperty ?: return null
        return metaProperty.type.takeIf { metaProperty.setter != null }
    }
}
