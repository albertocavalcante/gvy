package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsBlockMetadataTest {

    @Test
    fun `pipeline blocks should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("pipeline"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.PIPELINE_STRUCTURE,
            JenkinsBlockMetadata.getCategoryFor("pipeline"),
        )
    }

    @Test
    fun `wrapper blocks should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("withCredentials"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.WRAPPER,
            JenkinsBlockMetadata.getCategoryFor("withCredentials"),
        )
    }

    @Test
    fun `credential blocks should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("usernamePassword"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.CREDENTIAL,
            JenkinsBlockMetadata.getCategoryFor("usernamePassword"),
        )
    }

    @Test
    fun `regular methods should not be recognized`() {
        assertFalse(JenkinsBlockMetadata.isJenkinsBlock("println"))
        assertNull(JenkinsBlockMetadata.getCategoryFor("println"))
    }

    @Test
    fun `ALL_BLOCKS should contain all categories`() {
        val allBlocks = JenkinsBlockMetadata.ALL_BLOCKS

        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.PIPELINE_BLOCKS))
        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.WRAPPER_BLOCKS))
        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.CREDENTIAL_BLOCKS))

        val expectedSize = JenkinsBlockMetadata.PIPELINE_BLOCKS.size +
            JenkinsBlockMetadata.WRAPPER_BLOCKS.size +
            JenkinsBlockMetadata.CREDENTIAL_BLOCKS.size

        assertEquals(expectedSize, allBlocks.size, "Should have no duplicates")
    }
}
