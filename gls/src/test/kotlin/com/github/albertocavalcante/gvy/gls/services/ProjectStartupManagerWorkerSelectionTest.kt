package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionSource
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouter
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

class ProjectStartupManagerWorkerSelectionTest {

    @Test
    fun `select worker stores selected worker in compilation service`() {
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)
        val worker = descriptor(
            id = "in-process",
            range = GroovyVersionRange(parseGroovyVersion("2.0.0"), parseGroovyVersion("4.0.0")),
        )
        val manager = ProjectStartupManager(
            compilationService = compilationService,
            availableBuildTools = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            strategyRegistry = ProjectStrategyRegistry(),
            workerRouter = WorkerRouter(listOf(worker)),
        )

        manager.selectWorker(groovyInfo("3.0.0"), ServerConfiguration())

        verify(exactly = 1) { compilationService.updateSelectedWorker(worker) }
    }

    @Test
    fun `select worker clears selection when no compatible worker`() {
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)
        val worker = descriptor(
            id = "legacy",
            range = GroovyVersionRange(parseGroovyVersion("2.0.0"), parseGroovyVersion("2.4.0")),
        )
        val manager = ProjectStartupManager(
            compilationService = compilationService,
            availableBuildTools = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            strategyRegistry = ProjectStrategyRegistry(),
            workerRouter = WorkerRouter(listOf(worker)),
        )

        manager.selectWorker(groovyInfo("4.0.0"), ServerConfiguration())

        verify(exactly = 1) { compilationService.updateSelectedWorker(null) }
    }

    private fun groovyInfo(raw: String) = GroovyVersionInfo(parseGroovyVersion(raw), GroovyVersionSource.RUNTIME)

    private fun descriptor(id: String, range: GroovyVersionRange): WorkerDescriptor = WorkerDescriptor(
        id = id,
        supportedRange = range,
        capabilities = WorkerCapabilities(),
        connector = WorkerConnector.InProcess,
    )
}
