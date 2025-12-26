package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import com.github.albertocavalcante.groovylsp.version.GroovyVersionSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkerRouterTest {

    @Test
    fun `selects worker matching resolved groovy version and required features`() {
        val router = WorkerRouter(
            listOf(
                descriptor(
                    id = "worker-ast",
                    range = GroovyVersionRange(
                        parseVersion("2.5.0"),
                        parseVersion("4.0.0"),
                    ),
                    features = setOf(WorkerFeature.AST),
                ),
                descriptor(
                    id = "worker-symbols",
                    range = GroovyVersionRange(
                        parseVersion("3.0.0"),
                        parseVersion("4.0.0"),
                    ),
                    features = setOf(WorkerFeature.AST, WorkerFeature.SYMBOLS),
                ),
            ),
        )

        val selected = router.select(
            groovyVersionInfo("3.0.9"),
            requiredFeatures = setOf(WorkerFeature.SYMBOLS),
        )

        assertEquals("worker-symbols", selected?.id)
    }

    @Test
    fun `returns null when no worker matches`() {
        val router = WorkerRouter(
            listOf(
                descriptor(
                    id = "worker-legacy",
                    range = GroovyVersionRange(
                        parseVersion("2.0.0"),
                        parseVersion("2.5.0"),
                    ),
                    features = emptySet(),
                ),
            ),
        )

        val selected = router.select(
            groovyVersionInfo("3.0.0"),
            requiredFeatures = emptySet(),
        )

        assertNull(selected)
    }

    private fun groovyVersionInfo(version: String) = GroovyVersionInfo(
        version = parseVersion(version),
        source = GroovyVersionSource.DEPENDENCY,
    )

    private fun parseVersion(version: String): GroovyVersion =
        requireNotNull(GroovyVersion.parse(version)) { "Invalid Groovy version: $version" }

    private fun descriptor(id: String, range: GroovyVersionRange, features: Set<WorkerFeature>) = WorkerDescriptor(
        id = id,
        supportedRange = range,
        capabilities = WorkerCapabilities(features = features),
        connector = WorkerConnector.InProcess,
    )
}
