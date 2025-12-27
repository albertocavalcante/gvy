package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.test.parseGroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo
import com.github.albertocavalcante.groovylsp.version.GroovyVersionSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkerRouterFactoryTest {

    @Test
    fun `uses configured worker descriptors when provided`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.workers" to listOf(
                    mapOf(
                        "id" to "legacy",
                        "minVersion" to "2.0.0",
                        "maxVersion" to "2.4.0",
                        "connector" to "in-process",
                    ),
                ),
            ),
        )

        val router = WorkerRouterFactory.fromConfig(config)
        val info = GroovyVersionInfo(parseGroovyVersion("2.3.0"), GroovyVersionSource.RUNTIME)

        val selected = router.select(info)

        assertNotNull(selected)
        assertEquals("legacy", selected.id)
    }

    @Test
    fun `invalid maxVersion rejects descriptor`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.workers" to listOf(
                    mapOf(
                        "id" to "legacy",
                        "minVersion" to "2.0.0",
                        "maxVersion" to "invalid",
                        "connector" to "in-process",
                    ),
                ),
            ),
        )

        val router = WorkerRouterFactory.fromConfig(config)
        val info = GroovyVersionInfo(parseGroovyVersion("4.0.0"), GroovyVersionSource.RUNTIME)

        val selected = router.select(info)

        assertNotNull(selected)
        assertEquals("in-process-default", selected.id)
    }

    @Test
    fun `inverted version range rejects descriptor`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.workers" to listOf(
                    mapOf(
                        "id" to "legacy",
                        "minVersion" to "3.0.0",
                        "maxVersion" to "2.0.0",
                        "connector" to "in-process",
                    ),
                ),
            ),
        )

        val router = WorkerRouterFactory.fromConfig(config)
        val info = GroovyVersionInfo(parseGroovyVersion("3.0.0"), GroovyVersionSource.RUNTIME)

        val selected = router.select(info)

        assertNotNull(selected)
        assertEquals("in-process-default", selected.id)
    }

    @Test
    fun `duplicate ids ignore later entries`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.workers" to listOf(
                    mapOf(
                        "id" to "duplicate",
                        "minVersion" to "2.0.0",
                        "maxVersion" to "2.4.0",
                        "connector" to "in-process",
                    ),
                    mapOf(
                        "id" to "duplicate",
                        "minVersion" to "3.0.0",
                        "maxVersion" to "4.0.0",
                        "connector" to "in-process",
                    ),
                ),
            ),
        )

        val router = WorkerRouterFactory.fromConfig(config)
        val info = GroovyVersionInfo(parseGroovyVersion("3.5.0"), GroovyVersionSource.RUNTIME)

        val selected = router.select(info)

        assertNull(selected)
    }
}
