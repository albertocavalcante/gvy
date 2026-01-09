package com.github.albertocavalcante.groovylsp.compilation

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for ConfigurationFingerprint - ensures cache coherency through
 * configuration-aware fingerprinting (Issue #743).
 */
class ConfigurationFingerprintTest {

    @Test
    fun `same configuration produces same fingerprint`() {
        val deps = listOf(Path.of("/lib/a.jar"), Path.of("/lib/b.jar"))
        val sourceRoots = listOf(Path.of("/src/main/groovy"))

        val fp1 = ConfigurationFingerprint.compute(deps, sourceRoots)
        val fp2 = ConfigurationFingerprint.compute(deps, sourceRoots)

        assertEquals(fp1, fp2)
    }

    @Test
    fun `different dependencies produce different fingerprint`() {
        val sourceRoots = listOf(Path.of("/src/main/groovy"))

        val fp1 = ConfigurationFingerprint.compute(
            listOf(Path.of("/lib/a.jar")),
            sourceRoots,
        )
        val fp2 = ConfigurationFingerprint.compute(
            listOf(Path.of("/lib/b.jar")),
            sourceRoots,
        )

        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `different source roots produce different fingerprint`() {
        val deps = listOf(Path.of("/lib/a.jar"))

        val fp1 = ConfigurationFingerprint.compute(
            deps,
            listOf(Path.of("/src/main/groovy")),
        )
        val fp2 = ConfigurationFingerprint.compute(
            deps,
            listOf(Path.of("/src/test/groovy")),
        )

        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `dependency order affects fingerprint`() {
        val sourceRoots = listOf(Path.of("/src/main/groovy"))

        val fp1 = ConfigurationFingerprint.compute(
            listOf(Path.of("/lib/a.jar"), Path.of("/lib/b.jar")),
            sourceRoots,
        )
        val fp2 = ConfigurationFingerprint.compute(
            listOf(Path.of("/lib/b.jar"), Path.of("/lib/a.jar")),
            sourceRoots,
        )

        // Order matters for classpath - different order = different fingerprint
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `empty configuration produces valid fingerprint`() {
        val fp = ConfigurationFingerprint.compute(emptyList(), emptyList())

        // Should produce a valid SHA-256 hash (64 hex chars)
        assertEquals(64, fp.length)
        assert(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `fingerprint is deterministic across calls`() {
        val deps = listOf(
            Path.of("/home/user/.gradle/caches/modules-2/files-2.1/org.codehaus.groovy/groovy/4.0.0/groovy-4.0.0.jar"),
            Path.of(
                "/home/user/.gradle/caches/modules-2/files-2.1/org.spockframework/spock-core/2.3/spock-core-2.3.jar",
            ),
        )
        val sourceRoots = listOf(
            Path.of("/project/src/main/groovy"),
            Path.of("/project/src/test/groovy"),
        )

        val fingerprints = (1..10).map {
            ConfigurationFingerprint.compute(deps, sourceRoots)
        }

        // All should be identical
        assert(fingerprints.distinct().size == 1)
    }
}
