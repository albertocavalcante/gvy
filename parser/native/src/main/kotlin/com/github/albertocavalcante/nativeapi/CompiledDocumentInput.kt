// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import org.apache.groovy.parser.antlr4.Antlr4ParserPlugin
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ParserPlugin
import org.codehaus.groovy.control.ParserPluginFactory
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.FileReaderSource
import org.codehaus.groovy.control.io.ReaderSource
import org.codehaus.groovy.control.io.URLReaderSource
import org.codehaus.groovy.syntax.Reduction
import java.io.FileNotFoundException
import java.io.Reader
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** The selected compiler input, identified by its own reader and the decoded characters consumed for this AST. */
class CompiledDocumentInput private constructor(
    val uri: URI,
    private val reader: ReaderSource,
    private val chars: Long,
    private val hash: ByteArray?,
) {
    /** Reopen the compiler's own reader to preserve its charset and BOM handling. Bound work by its original size. */
    fun matchesCurrentText(editorText: String?, checkCancelled: () -> Unit): Boolean {
        if (reader is DocumentReaderSource) return editorText == reader.text
        if (editorText != null) return false
        return try {
            val attributes = Files.readAttributes(Path.of(uri), BasicFileAttributes::class.java)
            attributes.isRegularFile && reader.reader.use { matchesDecodedReader(it, checkCancelled) }
        } catch (error: FileNotFoundException) {
            if (Files.notExists(Path.of(uri))) false else throw error
        } catch (_: NoSuchFileException) {
            false
        }
    }

    private fun matchesDecodedReader(source: Reader, checkCancelled: () -> Unit): Boolean {
        val current = CharacterDigest()
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        var count = source.read(buffer)
        while (count >= 0) {
            checkCancelled()
            current.add(buffer, 0, count)
            if (current.chars > chars) return false
            count = source.read(buffer)
        }
        return current.chars == chars && current.finish().contentEquals(hash)
    }

    /** A later verdict prelude can only describe this compile if its decoded text also matches this witness. */
    fun matchesText(text: String): Boolean {
        if (reader is DocumentReaderSource) return text == reader.text
        if (text.length.toLong() != chars) return false
        val current = CharacterDigest()
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        var start = 0
        while (start < text.length) {
            val end = minOf(start + buffer.size, text.length)
            text.toCharArray(buffer, 0, start, end)
            current.add(buffer, 0, end - start)
            start = end
        }
        return current.finish().contentEquals(hash)
    }

    /** A retired reader's decoder and digest. This retains no compiler configuration, loader or AST. */
    internal class DiskRead(
        val uri: URI,
        private val decoder: Class<out ReaderSource>,
        private val encoding: String,
        private val chars: Long,
        private val hash: ByteArray,
    ) {
        fun matches(editorText: String?, checkCancelled: () -> Unit): Boolean {
            checkCancelled()
            val config = CompilerConfiguration().also { it.sourceEncoding = encoding }
            val reader = when (decoder) {
                WorkspaceFileReaderSource::class.java -> WorkspaceFileReaderSource(Path.of(uri).toFile(), config)
                FileReaderSource::class.java -> FileReaderSource(Path.of(uri).toFile(), config)
                URLReaderSource::class.java -> URLReaderSource(uri.toURL(), config)
                else -> error("Unrecognized captured reader ${decoder.name}")
            }
            return CompiledDocumentInput(uri, reader, chars, hash).matchesCurrentText(editorText, checkCancelled)
        }
    }

    companion object {
        private val DISK_DECODERS = setOf<Class<out ReaderSource>>(
            WorkspaceFileReaderSource::class.java,
            FileReaderSource::class.java,
            URLReaderSource::class.java,
        )

        /** Module identity comes directly from buildAST, including implicit source-root and locator loads. */
        fun of(module: ModuleNode): CompiledDocumentInput? {
            val unit = module.context ?: return null
            val reader = unit.source
            return module.getNodeMetaData<CompiledDocumentInput>(CompiledDocumentInput::class.java)
                ?.takeIf { it.reader === reader }
        }

        internal fun capture(
            factory: ParserPluginFactory,
            attempt: AttemptInputs,
        ): ParserPluginFactory = object : ParserPluginFactory() {
            override fun createParserPlugin(): ParserPlugin {
                val parser = factory.createParserPlugin()
                // This parser consumes the decoded reader in buildAST; another plugin may parse in parseCST.
                return if (parser is Antlr4ParserPlugin) {
                    CapturingParser(parser, attempt)
                } else {
                    attempt.record(null)
                    parser
                }
            }
        }
    }

    private class CapturingParser(
        private val parser: ParserPlugin,
        private val attempt: AttemptInputs,
    ) : ParserPlugin by parser {
        override fun buildAST(unit: SourceUnit, loader: ClassLoader, cst: Reduction?): ModuleNode {
            val original = unit.source
            val uri = unit.inputDocumentUri()
            if (original is DocumentReaderSource) {
                return parser.buildAST(unit, loader, cst).also { module ->
                    val witness =
                        CompiledDocumentInput(original.documentUri, original, original.text.length.toLong(), null)
                    module.putNodeMetaData(CompiledDocumentInput::class.java, witness)
                }
            }
            if (uri == null) {
                if (original !is GeneratedStubReaderSource) attempt.record(null)
                return parser.buildAST(unit, loader, cst)
            }
            val encoding = unit.configuration.sourceEncoding
            val input = AstReader(original)
            unit.source = input
            var module: ModuleNode? = null
            return try {
                parser.buildAST(unit, loader, cst).also { module = it }
            } finally {
                unit.source = original
                val digest = input.complete
                val hash = digest?.finish()
                val knownReader = original.javaClass in DISK_DECODERS
                val read = if (knownReader && digest != null && hash != null) {
                    DiskRead(uri, original.javaClass, encoding, digest.chars, hash)
                } else {
                    null
                }
                attempt.record(read)
                if (digest != null && hash != null) {
                    module?.putNodeMetaData(
                        CompiledDocumentInput::class.java,
                        CompiledDocumentInput(uri, original, digest.chars, hash),
                    )
                }
            }
        }
    }
}

/** Observe one complete reader only. Groovy's parser gets exactly the characters returned by its original reader. */
private class AstReader(private val original: ReaderSource) : ReaderSource by original {
    private var opened = false
    private var ambiguous = false
    var complete: CharacterDigest? = null
        private set

    override fun getReader(): Reader {
        val source = original.reader
        if (opened) {
            ambiguous = true
            complete = null
            return source
        }
        opened = true
        val digest = CharacterDigest()
        return object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                val count = source.read(buffer, offset, length)
                if (count < 0) {
                    if (!ambiguous) complete = digest
                } else {
                    digest.add(buffer, offset, count)
                }
                return count
            }

            override fun close() = source.close()
        }
    }
}

/** Hash UTF-16 code units directly. Chunk boundaries and surrogate pairs do not change the result. */
private class CharacterDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val bytes = ByteArray(DEFAULT_BUFFER_SIZE * 2)
    var chars = 0L
        private set

    fun add(buffer: CharArray, offset: Int, length: Int) {
        var start = offset
        val end = offset + length
        while (start < end) {
            val next = minOf(start + bytes.size / 2, end)
            var byte = 0
            for (i in start until next) {
                val code = buffer[i].code
                bytes[byte++] = (code ushr Byte.SIZE_BITS).toByte()
                bytes[byte++] = code.toByte()
            }
            digest.update(bytes, 0, byte)
            start = next
        }
        chars += length
    }

    fun finish(): ByteArray = digest.digest()
}
