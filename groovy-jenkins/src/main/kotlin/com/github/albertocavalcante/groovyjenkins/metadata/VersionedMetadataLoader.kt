package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.EnrichmentMetadataLoader
import org.slf4j.LoggerFactory

/**
 * Loads Jenkins metadata based on Jenkins LTS version.
 *
 * Supports version-specific metadata with fallback chain:
 * 1. Version-specific metadata (e.g., lts-2.479/)
 * 2. Default metadata
 * 3. Bundled metadata (always available)
 *
 * When loading merged metadata, enrichment and stable step definitions
 * take priority over bundled to provide more accurate/complete parameter information.
 */
class VersionedMetadataLoader {
    private val logger = LoggerFactory.getLogger(VersionedMetadataLoader::class.java)

    private val bundledLoader = BundledJenkinsMetadataLoader()
    private val enrichmentLoader = EnrichmentMetadataLoader()

    companion object {
        private const val METADATA_BASE_PATH = "/metadata"
        private const val DEFAULT_VERSION = "default"
    }

    /**
     * Load metadata for a specific Jenkins version.
     *
     * Falls back to bundled metadata if version-specific not available.
     *
     * @param jenkinsVersion The full Jenkins version (e.g., "2.479.3")
     * @return Loaded metadata
     */
    fun load(jenkinsVersion: String? = null): BundledJenkinsMetadata {
        val ltsVersion = jenkinsVersion?.let { extractLtsVersion(it) }

        if (ltsVersion != null) {
            // Try version-specific metadata first
            val versionPath = "$METADATA_BASE_PATH/lts-$ltsVersion/metadata.json"
            val versionResource = javaClass.getResourceAsStream(versionPath)

            if (versionResource != null) {
                logger.debug("Loading version-specific metadata for LTS {}", ltsVersion)
                // TODO: Parse version-specific JSON when available
                versionResource.close()
            } else {
                logger.debug("No version-specific metadata for LTS {}, using bundled", ltsVersion)
            }
        }

        // Fall back to bundled
        return bundledLoader.load()
    }

    /**
     * Load metadata merged with enrichment and stable step definitions.
     *
     * Priority order (highest to lowest):
     * 1. Enrichment metadata (curated descriptions, examples, valid values)
     * 2. Stable step definitions (hardcoded, most accurate)
     * 3. Version-specific metadata (when available)
     * 4. Bundled metadata (fallback)
     *
     * @param jenkinsVersion The Jenkins version (optional)
     * @return Merged metadata ready for use by LSP features
     */
    fun loadMerged(jenkinsVersion: String? = null): MergedJenkinsMetadata {
        val bundled = load(jenkinsVersion)
        val bundledWithStable = MetadataMerger.merge(bundled, StableStepDefinitions.all())
        val enrichment = enrichmentLoader.load()
        return MetadataMerger.mergeWithEnrichment(bundledWithStable, enrichment)
    }

    /**
     * Load metadata merged with all available sources.
     *
     * Priority order (highest to lowest):
     * 1. User overrides
     * 2. Dynamic classpath scan results
     * 3. Enrichment metadata
     * 4. Stable step definitions
     * 5. Version-specific metadata
     * 6. Bundled metadata
     *
     * @param jenkinsVersion The Jenkins version (optional)
     * @param dynamicSteps Steps from classpath scanning (optional)
     * @param userOverrides User-provided overrides (optional)
     * @return Fully merged metadata
     *
     * NOTE: This still returns BundledJenkinsMetadata for now to maintain compatibility.
     * Future versions may return MergedJenkinsMetadata once all consumers are updated.
     */
    fun loadMergedAll(
        jenkinsVersion: String? = null,
        dynamicSteps: Map<String, JenkinsStepMetadata>? = null,
        userOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
    ): BundledJenkinsMetadata {
        val bundled = load(jenkinsVersion)
        return MetadataMerger.mergeAll(
            bundled = bundled,
            versioned = null, // TODO: Load versioned when available
            stable = StableStepDefinitions.all(),
            dynamic = dynamicSteps,
            userOverrides = userOverrides,
        )
    }

    /**
     * Extract the major LTS version from a full version string.
     *
     * Examples:
     * - "2.479.3" -> "2.479"
     * - "2.479.1" -> "2.479"
     * - "2.480" -> "2.480" (weekly, returned as-is)
     *
     * @param fullVersion The full Jenkins version
     * @return The major.minor LTS version
     */
    fun extractLtsVersion(fullVersion: String): String {
        val parts = fullVersion.split(".")
        return when {
            parts.size >= 2 -> "${parts[0]}.${parts[1]}"
            else -> fullVersion
        }
    }

    /**
     * Get list of supported LTS versions.
     *
     * Returns versions that have metadata available in resources.
     *
     * @return List of supported version strings
     */
    fun getSupportedVersions(): List<String> {
        // For now, return default. When we add version-specific metadata,
        // this will scan the resources directory
        val versions = mutableListOf(DEFAULT_VERSION)

        // TODO: Scan metadata/ directory for lts-* folders
        // when we have version-specific metadata files

        return versions
    }

    /**
     * Check if a specific version has dedicated metadata.
     *
     * @param jenkinsVersion The Jenkins version to check
     * @return true if version-specific metadata exists
     */
    fun hasVersionSpecificMetadata(jenkinsVersion: String): Boolean {
        val ltsVersion = extractLtsVersion(jenkinsVersion)
        val versionPath = "$METADATA_BASE_PATH/lts-$ltsVersion/metadata.json"
        return javaClass.getResourceAsStream(versionPath) != null
    }
}
