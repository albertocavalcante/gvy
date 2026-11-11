package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParseCoordinatorTest {
    private fun tree(value: String) = CoreAstNodeDto(value, value, null, emptyList(), emptyMap())
    private fun snapshot(value: String) = ParseSnapshot(value, ParserType.CORE, null)

    @Test
    fun `debounce and conflation discard stale results from a noncooperative parser`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val invoked = mutableListOf<String>()
        val published = mutableListOf<ParseOutput>()
        val worker = StandardTestDispatcher(testScheduler)
        val coordinator = ParseCoordinator(this, worker, { input, _ ->
            invoked.add(input.source)
            if (input.source == "A") gate.await()
            ParseOutput(tree(input.source), emptyList())
        }, published::add, 300)
        coordinator.submit(snapshot("A"), deferred = false)
        runCurrent()
        assertEquals(listOf("A"), invoked)
        coordinator.submit(snapshot("B"), deferred = true)
        advanceTimeBy(300)
        runCurrent()
        coordinator.submit(snapshot("C"), deferred = true)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("A"), invoked)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("A", "C"), invoked)
        assertEquals(listOf("C"), published.map { it.tree?.id })
        coordinator.close()
        runCurrent()
    }

    @Test
    fun `close fences a late completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<ParseOutput>()
        val worker = StandardTestDispatcher(testScheduler)
        val coordinator = ParseCoordinator(this, worker, { input, _ ->
            gate.await()
            ParseOutput(tree(input.source), emptyList())
        }, published::add, 300)
        coordinator.submit(snapshot("A"), deferred = false)
        runCurrent()
        coordinator.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(published.isEmpty())
    }

    @Test
    fun `stale parser failure cannot replace newer parser result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<ParseOutput>()
        val worker = StandardTestDispatcher(testScheduler)
        val coordinator = ParseCoordinator(this, worker, { input, _ ->
            if (input.parser == ParserType.CORE) {
                gate.await()
                error("stale failure")
            }
            ParseOutput(tree("native"), emptyList())
        }, published::add, 300)
        coordinator.submit(snapshot("code"), deferred = false)
        runCurrent()
        coordinator.submit(ParseSnapshot("code", ParserType.NATIVE, null), deferred = false)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("native"), published.map { it.tree?.id })
        assertTrue(published.single().errors.isEmpty())
        coordinator.close()
        runCurrent()
    }

    @Test
    fun `unexpected current cancellation publishes failure instead of remaining busy`() = runTest {
        val published = mutableListOf<ParseOutput>()
        val worker = StandardTestDispatcher(testScheduler)
        val coordinator = ParseCoordinator(this, worker, { _, _ ->
            throw CancellationException("parser cancelled unexpectedly")
        }, published::add, 300)
        coordinator.submit(snapshot("code"), deferred = false)
        runCurrent()
        assertEquals(1, published.size)
        assertTrue(published.single().errors.isNotEmpty())
        coordinator.close()
        runCurrent()
    }
}
