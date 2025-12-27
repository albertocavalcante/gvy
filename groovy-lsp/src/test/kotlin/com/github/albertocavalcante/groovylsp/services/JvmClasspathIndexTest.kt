package com.github.albertocavalcante.groovylsp.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JvmClasspathIndexTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `indexes JDK classes when classpath entries are provided`() {
        val index = JvmClasspathIndex()

        val results = index.index(listOf(tempDir.toString()))

        assertThat(results).anyMatch { it.fullName == "java.lang.String" }
    }
}
