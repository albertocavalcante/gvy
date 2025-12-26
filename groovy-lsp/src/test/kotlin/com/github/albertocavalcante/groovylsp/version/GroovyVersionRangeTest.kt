package com.github.albertocavalcante.groovylsp.version

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroovyVersionRangeTest {

    @Test
    fun `range includes min and max`() {
        val min = GroovyVersion.parse("2.5.0")!!
        val max = GroovyVersion.parse("4.0.0")!!
        val range = GroovyVersionRange(min, max)

        assertTrue(range.contains(min))
        assertTrue(range.contains(max))
    }

    @Test
    fun `range rejects versions above max`() {
        val range = GroovyVersionRange(
            GroovyVersion.parse("2.5.0")!!,
            GroovyVersion.parse("4.0.0")!!,
        )

        assertFalse(range.contains(GroovyVersion.parse("4.1.0")!!))
    }

    @Test
    fun `range rejects versions below min`() {
        val range = GroovyVersionRange(
            GroovyVersion.parse("2.5.0")!!,
            GroovyVersion.parse("4.0.0")!!,
        )

        assertFalse(range.contains(GroovyVersion.parse("2.4.0")!!))
    }

    @Test
    fun `range without max accepts higher versions`() {
        val range = GroovyVersionRange(GroovyVersion.parse("2.5.0")!!)

        assertTrue(range.contains(GroovyVersion.parse("5.0.0")!!))
    }

    @Test
    fun `range requires max not below min`() {
        val min = GroovyVersion.parse("4.0.0")!!
        val max = GroovyVersion.parse("3.0.0")!!

        assertFailsWith<IllegalArgumentException> {
            GroovyVersionRange(min, max)
        }
    }
}
