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
    fun `post conditions should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("always"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.POST_CONDITION,
            JenkinsBlockMetadata.getCategoryFor("always"),
        )
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("success"))
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("failure"))
    }

    @Test
    fun `declarative options should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("disableConcurrentBuilds"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.DECLARATIVE_OPTION,
            JenkinsBlockMetadata.getCategoryFor("disableConcurrentBuilds"),
        )
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("skipDefaultCheckout"))
    }

    @Test
    fun `agent types should be recognized`() {
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("label"))
        assertEquals(
            JenkinsBlockMetadata.BlockCategory.AGENT_TYPE,
            JenkinsBlockMetadata.getCategoryFor("label"),
        )
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("docker"))
        assertTrue(JenkinsBlockMetadata.isJenkinsBlock("any"))
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
        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.POST_CONDITIONS))
        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.DECLARATIVE_OPTIONS))
        assertTrue(allBlocks.containsAll(JenkinsBlockMetadata.AGENT_TYPES))

        // Note: There may be duplicates across categories (e.g., 'timestamps' in both
        // WRAPPER_BLOCKS and DECLARATIVE_OPTIONS), so we can't assert exact size equality.
        // Instead, verify that each category is fully represented.
    }
}
