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

    fun selectedOrigin(resource: URI, binaryName: String): SourceOrigin.Bundled? {
        val manifest = metadata ?: return null
        val selected = parseJarClassResource(resource) ?: return null
        val runtimePath = runCatching { Path.of(runtimeContainer).toAbsolutePath().normalize() }.getOrNull()
            ?: return null
        if (selected.archive != runtimePath) return null
        val binaryEntry = binaryName.replace('.', '/') + ".class"
        if (selected.entryName != binaryEntry && !selected.entryName.endsWith("/$binaryEntry")) return null
        val expected = manifest.classes.getProperty(selected.entryName) ?: return null
        val actual = resource.toURL().openStream().use { copyAndDigest(it, MAX_BUNDLED_CLASS_BYTES) }
        if (actual != expected) return null
        return SourceOrigin.Bundled(manifest.component.id, selected.archive.toUri())
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

    private fun loadMetadata(): Metadata? {
        val properties = readProperties(MANIFEST_RESOURCE) ?: return null
        val classes = readProperties(CLASS_DIGESTS_RESOURCE) ?: return null
        val group = properties.getProperty("group") ?: return null
        val module = properties.getProperty("module") ?: return null
        val version = properties.getProperty("version") ?: return null
        val binary = properties.getProperty("binarySha256") ?: return null
        val sources = properties.getProperty("sourceSha256") ?: return null
        val resource = properties.getProperty("sourceResource") ?: return null
        if (!isSha256(binary) || !isSha256(sources) || !resource.startsWith("META-INF/gvy/sources/")) return null
        if (resource.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        val component = BundledSourceComponent(
            "$group:$module:$version",
            ResolvedArtifactCoordinates(group, module, version),
            binary,
            sources,
            resource,
        )
        return Metadata(component, classes)
    }

    private fun readProperties(resource: String): Properties? =
        resourceLoader.getResourceAsStream(resource)?.use { input ->
            val bytes = input.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1)
            if (bytes.size > MAX_MANIFEST_BYTES) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            Properties().apply { bytes.inputStream().reader(Charsets.UTF_8).use(::load) }
        }

    private data class Metadata(val component: BundledSourceComponent, val classes: Properties)
}
