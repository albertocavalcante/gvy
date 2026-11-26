// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.FileReaderSource
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.control.io.URLReaderSource
import java.net.URI

/** Preserve the input URI independently of Groovy's source name and data URI. */
internal class DocumentReaderSource(val documentUri: URI, text: String, configuration: CompilerConfiguration) :
    StringReaderSource(text, configuration)

/** Original editor URI or file input selected by this compiler. Synthetic stubs have no document. */
fun SourceUnit.inputDocumentUri(): URI? = when (val reader = source) {
    is DocumentReaderSource -> reader.documentUri
    is FileReaderSource -> reader.getURI()
    is URLReaderSource -> reader.getURI()?.takeIf { it.scheme.equals("file", ignoreCase = true) }
    else -> null
}
