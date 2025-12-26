package com.github.albertocavalcante.groovylsp.version

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroovyVersionTest {

    @Test
    fun `parse version with patch`() {
        val version = GroovyVersion.parse("4.0.29")
        assertNotNull(version)
        assertEquals(4, version.major)
        assertEquals(0, version.minor)
        assertEquals(29, version.patch)
    }

    @Test
    fun `parse version without patch defaults to zero`() {
        val version = GroovyVersion.parse("2.5")
        assertNotNull(version)
        assertEquals(2, version.major)
        assertEquals(5, version.minor)
        assertEquals(0, version.patch)
    }

    @Test
    fun `qualifier without minor keeps zeroed minor and patch`() {
        val version = GroovyVersion.parse("4-rc-1")
        assertNotNull(version)
        assertEquals(4, version.major)
        assertEquals(0, version.minor)
        assertEquals(0, version.patch)
    }

    @Test
    fun `parse invalid version returns null`() {
        assertNull(GroovyVersion.parse("not-a-version"))
    }

    @Test
    fun `release is newer than rc`() {
        val release = GroovyVersion.parse("4.0.0")!!
        val rc = GroovyVersion.parse("4.0.0-rc-1")!!
        assertTrue(release > rc)
    }

    @Test
    fun `snapshot is newer than release`() {
        val release = GroovyVersion.parse("4.0.0")!!
        val snapshot = GroovyVersion.parse("4.0.0-SNAPSHOT")!!
        assertTrue(snapshot > release)
    }
}
