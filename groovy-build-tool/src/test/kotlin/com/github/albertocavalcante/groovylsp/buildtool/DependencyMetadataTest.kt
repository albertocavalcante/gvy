package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DependencyMetadataTest {
    @Test
    fun `normalizeScope converts Gradle scopes`() {
        assertEquals("compile", DependencyMetadata.normalizeScope("implementation"))
        assertEquals("compile", DependencyMetadata.normalizeScope("api"))
        assertEquals("test", DependencyMetadata.normalizeScope("testImplementation"))
        assertEquals("provided", DependencyMetadata.normalizeScope("compileOnly"))
        assertEquals("runtime", DependencyMetadata.normalizeScope("runtimeOnly"))
    }

    @Test
    fun `normalizeScope converts Maven scopes`() {
        assertEquals("compile", DependencyMetadata.normalizeScope("compile"))
        assertEquals("test", DependencyMetadata.normalizeScope("test"))
        assertEquals("provided", DependencyMetadata.normalizeScope("provided"))
        assertEquals("runtime", DependencyMetadata.normalizeScope("runtime"))
        assertEquals("provided", DependencyMetadata.normalizeScope("system"))
    }

    @Test
    fun `normalizeScope handles null and empty`() {
        assertEquals("compile", DependencyMetadata.normalizeScope(null))
        assertEquals("compile", DependencyMetadata.normalizeScope(""))
    }

    @Test
    fun `normalizeScope passes through unknown scopes`() {
        assertEquals("custom", DependencyMetadata.normalizeScope("custom"))
        assertEquals("unknown", DependencyMetadata.normalizeScope("UNKNOWN"))
    }
}
