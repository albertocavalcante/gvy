package com.github.albertocavalcante.groovyspock

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpockBlockTest {

    @Test
    fun `fromLabel parses valid block labels`() {
        assertEquals(SpockBlock.GIVEN, SpockBlock.fromLabel("given"))
        assertEquals(SpockBlock.SETUP, SpockBlock.fromLabel("setup"))
        assertEquals(SpockBlock.WHEN, SpockBlock.fromLabel("when"))
        assertEquals(SpockBlock.THEN, SpockBlock.fromLabel("then"))
        assertEquals(SpockBlock.EXPECT, SpockBlock.fromLabel("expect"))
        assertEquals(SpockBlock.WHERE, SpockBlock.fromLabel("where"))
        assertEquals(SpockBlock.CLEANUP, SpockBlock.fromLabel("cleanup"))
        assertEquals(SpockBlock.AND, SpockBlock.fromLabel("and"))
    }

    @Test
    fun `fromLabel is case insensitive`() {
        assertEquals(SpockBlock.GIVEN, SpockBlock.fromLabel("GIVEN"))
        assertEquals(SpockBlock.WHEN, SpockBlock.fromLabel("When"))
        assertEquals(SpockBlock.THEN, SpockBlock.fromLabel("THEN"))
    }

    @Test
    fun `fromLabel returns null for invalid labels`() {
        assertNull(SpockBlock.fromLabel("invalid"))
        assertNull(SpockBlock.fromLabel(""))
        assertNull(SpockBlock.fromLabel("def"))
        assertNull(SpockBlock.fromLabel("class"))
    }
}
