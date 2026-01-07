package com.github.groovylsp.bsp.client

import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.CompileProvider
import ch.epfl.scala.bsp4j.RunProvider
import ch.epfl.scala.bsp4j.TestProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BspCapabilitiesTest {

    @Test
    fun `supportsCompile returns true when compile provider has language IDs`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy", "java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsCompile())
    }

    @Test
    fun `supportsCompile returns false when compile provider is null`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsCompile())
    }

    @Test
    fun `supportsCompile returns false when compile provider has empty language list`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(emptyList())
        }
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsCompile())
    }

    @Test
    fun `supportsTest returns true when test provider has language IDs`() {
        val serverCaps = BuildServerCapabilities().apply {
            testProvider = TestProvider(listOf("groovy", "java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsTest())
    }

    @Test
    fun `supportsTest returns false when test provider is null`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsTest())
    }

    @Test
    fun `supportsRun returns true when run provider has language IDs`() {
        val serverCaps = BuildServerCapabilities().apply {
            runProvider = RunProvider(listOf("groovy", "java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsRun())
    }

    @Test
    fun `supportsRun returns false when run provider is null`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsRun())
    }

    @Test
    fun `supportsDependencySources returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            dependencySourcesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsDependencySources())
    }

    @Test
    fun `supportsDependencySources returns false by default`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsDependencySources())
    }

    @Test
    fun `supportsDependencyModules returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            dependencyModulesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsDependencyModules())
    }

    @Test
    fun `supportsDependencyModules returns false by default`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.supportsDependencyModules())
    }

    @Test
    fun `supportsResources returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            resourcesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsResources())
    }

    @Test
    fun `supportsOutputPaths returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            outputPathsProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsOutputPaths())
    }

    @Test
    fun `supportsInverseSources returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            inverseSourcesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsInverseSources())
    }

    @Test
    fun `canReload returns true when explicitly enabled`() {
        val serverCaps = BuildServerCapabilities().apply {
            canReload = true
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.canReload())
    }

    @Test
    fun `canReload returns false by default`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertFalse(capabilities.canReload())
    }

    @Test
    fun `supportedCompileLanguages returns configured languages`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy", "java", "kotlin"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertEquals(listOf("groovy", "java", "kotlin"), capabilities.supportedCompileLanguages())
    }

    @Test
    fun `supportedCompileLanguages returns empty list when compile not supported`() {
        val serverCaps = BuildServerCapabilities()
        val capabilities = BspCapabilities(serverCaps)

        assertEquals(emptyList(), capabilities.supportedCompileLanguages())
    }

    @Test
    fun `supportedTestLanguages returns configured languages`() {
        val serverCaps = BuildServerCapabilities().apply {
            testProvider = TestProvider(listOf("groovy", "java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertEquals(listOf("groovy", "java"), capabilities.supportedTestLanguages())
    }

    @Test
    fun `supportedRunLanguages returns configured languages`() {
        val serverCaps = BuildServerCapabilities().apply {
            runProvider = RunProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertEquals(listOf("groovy"), capabilities.supportedRunLanguages())
    }

    @Test
    fun `supportsCompileLanguage checks for specific language support`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy", "java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsCompileLanguage("groovy"))
        assertTrue(capabilities.supportsCompileLanguage("java"))
        assertFalse(capabilities.supportsCompileLanguage("kotlin"))
    }

    @Test
    fun `supportsTestLanguage checks for specific language support`() {
        val serverCaps = BuildServerCapabilities().apply {
            testProvider = TestProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsTestLanguage("groovy"))
        assertFalse(capabilities.supportsTestLanguage("java"))
    }

    @Test
    fun `supportsRunLanguage checks for specific language support`() {
        val serverCaps = BuildServerCapabilities().apply {
            runProvider = RunProvider(listOf("java"))
        }
        val capabilities = BspCapabilities(serverCaps)

        assertTrue(capabilities.supportsRunLanguage("java"))
        assertFalse(capabilities.supportsRunLanguage("groovy"))
    }

    @Test
    fun `raw returns the underlying capabilities object`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy"))
            canReload = true
        }
        val capabilities = BspCapabilities(serverCaps)

        val raw = capabilities.raw()
        assertEquals(serverCaps, raw)
        assertTrue(raw.canReload)
        assertEquals(listOf("groovy"), raw.compileProvider.languageIds)
    }

    @Test
    fun `full capability set works together`() {
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy", "java"))
            testProvider = TestProvider(listOf("groovy", "java"))
            runProvider = RunProvider(listOf("groovy", "java"))
            dependencySourcesProvider = true
            dependencyModulesProvider = true
            resourcesProvider = true
            outputPathsProvider = true
            inverseSourcesProvider = true
            canReload = true
        }
        val capabilities = BspCapabilities(serverCaps)

        // All capabilities should be supported
        assertTrue(capabilities.supportsCompile())
        assertTrue(capabilities.supportsTest())
        assertTrue(capabilities.supportsRun())
        assertTrue(capabilities.supportsDependencySources())
        assertTrue(capabilities.supportsDependencyModules())
        assertTrue(capabilities.supportsResources())
        assertTrue(capabilities.supportsOutputPaths())
        assertTrue(capabilities.supportsInverseSources())
        assertTrue(capabilities.canReload())
    }
}
