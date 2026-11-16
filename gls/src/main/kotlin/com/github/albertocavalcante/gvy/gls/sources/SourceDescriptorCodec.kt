// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Properties

/** Versioned metadata only: no compiler snapshot, classloader or mutable editor state is persisted. */
internal object SourceDescriptorCodec {
    fun identity(descriptor: SourceDocumentDescriptor): String {
        val properties = encode(URI.create("urn:gvy-source"), descriptor)
        return properties.stringPropertyNames()
            .filter { it !in PRESENTATION_FIELDS }
            .sorted()
            .joinToString("") { key ->
                val value = properties.getProperty(key)
                key.length.toString() + ":" + key + value.length + ":" + value
            }
    }

    private val PRESENTATION_FIELDS = setOf("version", "uri", "contentPath", "displayName")

    fun encode(uri: URI, descriptor: SourceDocumentDescriptor): Properties = Properties().apply {
        setProperty("version", "1")
        setProperty("uri", uri.toString())
        setProperty("sourceDigest", descriptor.sourceDigest)
        setProperty("contentDigest", descriptor.contentDigest)
        setProperty("entryName", descriptor.entryName)
        setProperty("contentPath", descriptor.contentPath.toString())
        setProperty("context.id", descriptor.context.id)
        descriptor.context.documentUri?.let { setProperty("context.documentUri", it.toString()) }
        descriptor.context.compileFingerprint?.let { setProperty("context.compileFingerprint", it) }
        setProperty("displayName", descriptor.displayName)
        setProperty("languageId", descriptor.languageId)
        encodeOrigin(descriptor.origin, this)
    }.also(::checkRawBudget)

    fun serialize(uri: URI, descriptor: SourceDocumentDescriptor): ByteArray {
        val output = BoundedMetadataOutput()
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            encode(uri, descriptor).store(writer, null)
        }
        return output.toByteArray()
    }

    private fun checkRawBudget(properties: Properties) {
        var remaining = MAX_METADATA_BYTES
        for (key in properties.stringPropertyNames()) {
            val size = key.length.toLong() + properties.getProperty(key).length
            if (size > remaining) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            remaining -= size.toInt()
        }
    }

    private class BoundedMetadataOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= MAX_METADATA_BYTES) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > MAX_METADATA_BYTES - count) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            super.write(bytes, offset, length)
        }
    }

    fun decode(uri: URI, values: Properties): SourceDocumentDescriptor? {
        if (values.getProperty("version") != "1" || values.getProperty("uri") != uri.toString()) return null
        return SourceDocumentDescriptor(
            sourceDigest = values.required("sourceDigest"),
            contentDigest = values.required("contentDigest"),
            entryName = values.required("entryName"),
            contentPath = Path.of(values.required("contentPath")),
            origin = decodeOrigin(values),
            context = SourceContextIdentity(
                values.required("context.id"),
                values.getProperty("context.documentUri")?.let(URI::create),
                values.getProperty("context.compileFingerprint"),
            ),
            displayName = values.required("displayName"),
            languageId = values.required("languageId"),
        )
    }

    private fun encodeOrigin(origin: SourceOrigin, values: Properties) = with(values) {
        when (origin) {
            is SourceOrigin.Archive -> encodeArchive(origin, values)
            is SourceOrigin.Bundled -> {
                setProperty("origin.kind", "bundled")
                setProperty("origin.component", origin.componentId)
                setProperty("origin.container", origin.binaryContainer.toString())
            }
            is SourceOrigin.Jdk -> {
                setProperty("origin.kind", "jdk")
                setProperty("origin.home", origin.imageHome.toString())
                setProperty("origin.module", origin.module)
            }
            is SourceOrigin.Unknown -> {
                setProperty("origin.kind", "unknown")
                setProperty("origin.resource", origin.resource.toString())
            }
        }
    }

    private fun encodeArchive(origin: SourceOrigin.Archive, values: Properties) = with(values) {
        setProperty("origin.kind", "archive")
        setProperty("origin.path", origin.binaryJar.toString())
        origin.coordinates?.let { coordinates ->
            setProperty("origin.group", coordinates.group)
            setProperty("origin.module", coordinates.module)
            setProperty("origin.version", coordinates.version)
            coordinates.classifier?.let { classifier -> setProperty("origin.classifier", classifier) }
            coordinates.extension?.let { extension -> setProperty("origin.extension", extension) }
        }
        origin.observedStamp?.let { stamp ->
            setProperty("origin.size", stamp.size.toString())
            setProperty("origin.modified", stamp.lastModifiedMillis.toString())
            stamp.files?.let { files -> setProperty("origin.files", files.toString()) }
        }
        origin.binarySha256?.let { setProperty("origin.binarySha256", it) }
    }

    private fun decodeOrigin(values: Properties): SourceOrigin = when (values.required("origin.kind")) {
        "archive" -> SourceOrigin.Archive(
            Path.of(values.required("origin.path")),
            values.getProperty("origin.group")?.let { group ->
                ResolvedArtifactCoordinates(
                    group,
                    values.required("origin.module"),
                    values.required("origin.version"),
                    values.getProperty("origin.classifier"),
                    values.getProperty("origin.extension"),
                )
            },
            values.getProperty("origin.size")?.let { size ->
                ArtifactStamp(
                    size.toLong(),
                    values.required("origin.modified").toLong(),
                    values.getProperty("origin.files")?.toLong(),
                )
            },
            values.getProperty("origin.binarySha256")?.lowercase()?.also { digest ->
                require(isSha256(digest)) { "Invalid binary source identity" }
            },
        )
        "bundled" -> SourceOrigin.Bundled(
            values.required("origin.component"),
            URI.create(values.required("origin.container")),
        )
        "jdk" -> SourceOrigin.Jdk(Path.of(values.required("origin.home")), values.required("origin.module"))
        "unknown" -> SourceOrigin.Unknown(URI.create(values.required("origin.resource")))
        else -> throw IllegalArgumentException("Unknown source origin")
    }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Missing source descriptor field: " + key }

    private const val MAX_METADATA_BYTES = 64 * 1024
}
