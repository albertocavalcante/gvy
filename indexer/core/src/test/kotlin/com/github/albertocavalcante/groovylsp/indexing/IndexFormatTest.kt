package com.github.albertocavalcante.groovylsp.indexing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IndexFormatTest {

    @Test
    fun `fromString parses known formats case-insensitively`() {
        assertEquals(IndexFormat.SCIP, IndexFormat.fromString("scip"))
        assertEquals(IndexFormat.LSIF, IndexFormat.fromString("LSIF"))
    }

    @Test
    fun `fromString returns null for unknown values`() {
        assertNull(IndexFormat.fromString("unknown"))
    }
}
