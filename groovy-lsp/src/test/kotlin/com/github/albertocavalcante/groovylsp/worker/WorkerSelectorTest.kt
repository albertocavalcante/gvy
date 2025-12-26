package com.github.albertocavalcante.groovylsp.worker

import com.github.albertocavalcante.groovylsp.version.GroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkerSelectorTest {

    @Test
    fun `selects worker with matching version and required feature`() {
        val selector = WorkerSelector(
            listOf(
                workerDescriptor(
                    id = "worker-legacy",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("2.5.0")!!,
                        GroovyVersion.parse("3.0.0")!!,
                    ),
                    features = setOf(WorkerFeature.AST),
                ),
                workerDescriptor(
                    id = "worker-modern",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("3.0.0")!!,
                        GroovyVersion.parse("4.0.0")!!,
                    ),
                    features = setOf(WorkerFeature.AST, WorkerFeature.SYMBOLS),
                ),
            ),
        )

        val selected = selector.select(
            requestedVersion = GroovyVersion.parse("3.0.9")!!,
            requiredFeatures = setOf(WorkerFeature.SYMBOLS),
        )

        assertEquals("worker-modern", selected?.id)
    }

    @Test
    fun `prefers most specific range when multiple workers match`() {
        val selector = WorkerSelector(
            listOf(
                workerDescriptor(
                    id = "worker-wide",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("2.0.0")!!,
                        GroovyVersion.parse("4.0.0")!!,
                    ),
                    features = setOf(WorkerFeature.AST),
                ),
                workerDescriptor(
                    id = "worker-narrow",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("3.0.0")!!,
                        GroovyVersion.parse("3.5.0")!!,
                    ),
                    features = setOf(WorkerFeature.AST),
                ),
            ),
        )

        val selected = selector.select(
            requestedVersion = GroovyVersion.parse("3.2.0")!!,
            requiredFeatures = emptySet(),
        )

        assertEquals("worker-narrow", selected?.id)
    }

    @Test
    fun `prefers bounded range over unbounded when min matches`() {
        val selector = WorkerSelector(
            listOf(
                workerDescriptor(
                    id = "worker-unbounded",
                    range = GroovyVersionRange(GroovyVersion.parse("3.0.0")!!),
                    features = emptySet(),
                ),
                workerDescriptor(
                    id = "worker-bounded",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("3.0.0")!!,
                        GroovyVersion.parse("4.0.0")!!,
                    ),
                    features = emptySet(),
                ),
            ),
        )

        val selected = selector.select(
            requestedVersion = GroovyVersion.parse("3.0.9")!!,
            requiredFeatures = emptySet(),
        )

        assertEquals("worker-bounded", selected?.id)
    }

    @Test
    fun `returns null when no worker matches range`() {
        val selector = WorkerSelector(
            listOf(
                workerDescriptor(
                    id = "worker-legacy",
                    range = GroovyVersionRange(
                        GroovyVersion.parse("2.0.0")!!,
                        GroovyVersion.parse("2.5.0")!!,
                    ),
                    features = setOf(WorkerFeature.AST),
                ),
            ),
        )

        val selected = selector.select(
            requestedVersion = GroovyVersion.parse("3.0.0")!!,
            requiredFeatures = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `throws when duplicate worker ids are registered`() {
        assertFailsWith<IllegalArgumentException> {
            WorkerSelector(
                listOf(
                    workerDescriptor(
                        id = "worker-dup",
                        range = GroovyVersionRange(GroovyVersion.parse("2.0.0")!!),
                        features = emptySet(),
                    ),
                    workerDescriptor(
                        id = "worker-dup",
                        range = GroovyVersionRange(GroovyVersion.parse("3.0.0")!!),
                        features = emptySet(),
                    ),
                ),
            )
        }
    }

    private fun workerDescriptor(
        id: String,
        range: GroovyVersionRange,
        features: Set<WorkerFeature>,
    ): WorkerDescriptor = WorkerDescriptor(
        id = id,
        supportedRange = range,
        capabilities = WorkerCapabilities(features = features),
        connector = WorkerConnector.InProcess,
    )
}
