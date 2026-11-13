// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties

private const val MANIFEST_RESOURCE = "META-INF/gvy/sources/groovy.properties"
private const val CLASS_DIGESTS_RESOURCE = "META-INF/gvy/sources/groovy-class-digests.properties"
private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
private const val MAX_BUNDLED_CLASS_BYTES = 2L * 1024 * 1024
private const val MAX_COMPONENT_BYTES = 64L * 1024 * 1024
private val REQUIRED_METADATA_FIELDS =
    listOf("group", "module", "version", "binarySha256", "sourceSha256", "sourceResource")

data class BundledSourceComponent(
    val id: String,
    val coordinates: ResolvedArtifactCoordinates,
    val binaryDigest: String,
    val sourceDigest: String,
    val sourceResource: String,
)

/** Packaging supplies exact component and entry digests; shaded resource paths alone cannot establish origin. */
class BundledSourceCatalog(
    private val resourceLoader: ClassLoader = BundledSourceCatalog::class.java.classLoader,
    private val runtimeContainer: URI = BundledSourceCatalog::class.java.protectionDomain.codeSource.location.toURI(),
) {
    private val metadata: Metadata? by lazy { loadMetadata() }

    fun component(): BundledSourceComponent? = metadata?.component

    fun selectedOrigin(resource: URI, binaryName: String): SourceOrigin.Bundled? = metadata?.let { manifest ->
        val runtimePath = runCatching { Path.of(runtimeContainer).toAbsolutePath().normalize() }.getOrNull()
        parseJarClassResource(resource)
            ?.takeIf { selected -> runtimePath == selected.archive && selected.matchesBinaryName(binaryName) }
            ?.let { selected -> selected.verifiedOrigin(resource, manifest) }
    }

    fun sourceInput(origin: SourceOrigin.Bundled): SourceArchiveInput? {
        val component = metadata?.component ?: return null
        if (origin.componentId != component.id) return null
        return SourceArchiveInput.VerifiedResource(component.sourceDigest) {
            resourceLoader.getResourceAsStream(component.sourceResource)
                ?: throw IOException("Bundled source resource is missing")
        }
    }

    /** Unshaded execution may reuse bundled sources only after proving the complete actual core binary. */
    fun sourceInputForArchive(origin: SourceOrigin.Archive, binaryName: String): SourceArchiveInput? {
        val manifest = metadata ?: return null
        if (!manifest.classes.containsKey(binaryName.replace('.', '/') + ".class")) return null
        val binary = origin.binaryJar.toRealPath()
        if (
            !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(binary) > MAX_COMPONENT_BYTES
        ) {
            return null
        }
        val actual = Files.newInputStream(binary).use { copyAndDigest(it, MAX_COMPONENT_BYTES) }
        if (actual != manifest.component.binaryDigest) return null
        return sourceInput(SourceOrigin.Bundled(manifest.component.id, origin.binaryJar.toUri()))
    }

    private fun loadMetadata(): Metadata? = readProperties(MANIFEST_RESOURCE)?.let { properties ->
        readProperties(CLASS_DIGESTS_RESOURCE)?.let { classes -> metadataFrom(properties, classes) }
    }

    private fun metadataFrom(properties: Properties, classes: Properties): Metadata? {
        val fields = MetadataFields.from(properties) ?: return null
        if (!fields.hasValidDigests() || !fields.hasSafeSourceResource()) return null
        return Metadata(
            BundledSourceComponent(
                "${fields.group}:${fields.module}:${fields.version}",
                ResolvedArtifactCoordinates(fields.group, fields.module, fields.version),
                fields.binaryDigest,
                fields.sourceDigest,
                fields.sourceResource,
            ),
            classes,
        )
    }

    private fun readProperties(resource: String): Properties? =
        resourceLoader.getResourceAsStream(resource)?.use { input ->
            val bytes = input.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1)
            if (bytes.size > MAX_MANIFEST_BYTES) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            Properties().apply { bytes.inputStream().reader(Charsets.UTF_8).use(::load) }
        }

    private data class Metadata(val component: BundledSourceComponent, val classes: Properties)

    private data class MetadataFields(
        val group: String,
        val module: String,
        val version: String,
        val binaryDigest: String,
        val sourceDigest: String,
        val sourceResource: String,
    ) {
        fun hasValidDigests(): Boolean = isSha256(binaryDigest) && isSha256(sourceDigest)

        fun hasSafeSourceResource(): Boolean = sourceResource.startsWith("META-INF/gvy/sources/") &&
            sourceResource.split('/').none(::isUnsafeMetadataPathSegment)

        companion object {
            fun from(properties: Properties): MetadataFields? {
                val propertyNames = properties.stringPropertyNames()
                if (!REQUIRED_METADATA_FIELDS.all(propertyNames::contains)) return null
                return MetadataFields(
                    properties.getProperty("group", ""),
                    properties.getProperty("module", ""),
                    properties.getProperty("version", ""),
                    properties.getProperty("binarySha256", ""),
                    properties.getProperty("sourceSha256", ""),
                    properties.getProperty("sourceResource", ""),
                )
            }
        }
    }

    private fun JarClassResource.matchesBinaryName(binaryName: String): Boolean {
        val binaryEntry = binaryName.replace('.', '/') + ".class"
        return entryName == binaryEntry || entryName.endsWith("/$binaryEntry")
    }

    private fun JarClassResource.verifiedOrigin(resource: URI, manifest: Metadata): SourceOrigin.Bundled? =
        manifest.classes.getProperty(entryName)?.let { expected ->
            val actual = resource.toURL().openStream().use { copyAndDigest(it, MAX_BUNDLED_CLASS_BYTES) }
            if (actual == expected) SourceOrigin.Bundled(manifest.component.id, archive.toUri()) else null
        }
}

private fun isUnsafeMetadataPathSegment(segment: String): Boolean =
    segment.isEmpty() || isDotMetadataPathSegment(segment)

private fun isDotMetadataPathSegment(segment: String): Boolean = segment == "." || segment == ".."
