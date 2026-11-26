// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.FileReaderSource
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.control.io.URLReaderSource
import java.io.File
import java.io.Reader
import java.net.URI
import java.nio.charset.Charset

/** Preserve the input URI independently of Groovy's source name and data URI. */
internal class DocumentReaderSource(val documentUri: URI, val text: String, configuration: CompilerConfiguration) :
    StringReaderSource(text, configuration) {
    internal var attempts: NativeRetryInputs? = null
}

/** A generated missing-class substitute has no physical source identity. */
internal class GeneratedStubReaderSource(text: String, configuration: CompilerConfiguration) :
    StringReaderSource(text, configuration)

/** GROOVY-12539: Groovy 4.0.33's FileReaderSource checks EF BB FF when opening a UTF-8 file. */
internal class WorkspaceFileReaderSource(file: File, configuration: CompilerConfiguration) :
    FileReaderSource(file, configuration) {
    override fun getReader(): Reader {
        val encoding = Charset.forName(configuration.sourceEncoding)
        if (encoding != Charsets.UTF_8) return super.getReader()
        val stream = getFile().inputStream().buffered()
        var transferred = false
        try {
            stream.mark(UTF8_BOM.size)
            if (!stream.readNBytes(UTF8_BOM.size).contentEquals(UTF8_BOM)) stream.reset()
            return stream.reader(encoding).also { transferred = true }
        } finally {
            if (!transferred) stream.close()
        }
    }

    private companion object {
        val UTF8_BOM = "\uFEFF".toByteArray(Charsets.UTF_8)
    }
}

/** Original editor URI or file input selected by this compiler. Synthetic stubs have no document. */
fun SourceUnit.inputDocumentUri(): URI? = when (val reader = source) {
    is DocumentReaderSource -> reader.documentUri
    is FileReaderSource -> reader.getURI()
    is URLReaderSource -> reader.getURI()?.takeIf { it.scheme.equals("file", ignoreCase = true) }
    else -> null
}

/** The primary reader is available even when syntax errors prevented creation of its module. */
fun SourceUnit.matchesPrimaryInput(uri: URI, content: String): Boolean {
    val input = source
    if (input is DocumentReaderSource) return input.documentUri == uri && input.text == content
    // Older in-memory workers use Groovy's unmodified string reader and name the unit from the request.
    if (input?.javaClass != StringReaderSource::class.java || name != (uri.path ?: uri.toString())) return false
    return input.reader.use { reader ->
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        var offset = 0
        var count = reader.read(buffer)
        while (count >= 0) {
            if (count > content.length - offset) return false
            for (index in 0 until count) {
                if (buffer[index] != content[offset + index]) return false
            }
            offset += count
            count = reader.read(buffer)
        }
        offset == content.length
    }
}
