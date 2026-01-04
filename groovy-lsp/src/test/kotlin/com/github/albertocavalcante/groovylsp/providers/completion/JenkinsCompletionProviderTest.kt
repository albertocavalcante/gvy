package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JenkinsCompletionProviderTest {

    @Test
    fun `findJenkinsGlobalVariable returns null when name matches but inferred type is shadowed`() {
        val env = MergedGlobalVariable(
            name = "env",
            type = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
            extractedDocumentation = null,
            enrichedDescription = null,
            documentationUrl = null,
            properties = emptyMap(),
        )
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.0",
            steps = emptyMap(),
            globalVariables = mapOf("env" to env),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val resolved = JenkinsCompletionProvider.findJenkinsGlobalVariable(
            name = "env",
            type = "java.lang.String",
            metadata = metadata,
        )
        assertNull(resolved)
    }

    @Test
    fun `findJenkinsGlobalVariable allows Object type fallback`() {
        val env = MergedGlobalVariable(
            name = "env",
            type = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
            extractedDocumentation = null,
            enrichedDescription = null,
            documentationUrl = null,
            properties = emptyMap(),
        )
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.0",
            steps = emptyMap(),
            globalVariables = mapOf("env" to env),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val resolved = JenkinsCompletionProvider.findJenkinsGlobalVariable(
            name = "env",
            type = "java.lang.Object",
            metadata = metadata,
        )
        assertNotNull(resolved)
        assertEquals("env", resolved.name)
    }

    @Test
    fun `findJenkinsGlobalVariable falls back by type`() {
        val currentBuild = MergedGlobalVariable(
            name = "currentBuild",
            type = "org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper",
            extractedDocumentation = null,
            enrichedDescription = null,
            documentationUrl = null,
            properties = emptyMap(),
        )
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.0",
            steps = emptyMap(),
            globalVariables = mapOf("currentBuild" to currentBuild),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val resolved = JenkinsCompletionProvider.findJenkinsGlobalVariable(
            name = null,
            type = "org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper",
            metadata = metadata,
        )
        assertNotNull(resolved)
        assertEquals("currentBuild", resolved.name)
    }
}
