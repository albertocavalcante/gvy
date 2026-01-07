package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.StatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class CompileGatekeeperTest {

    @AfterEach
    fun cleanup() {
        // Reset gatekeeper state between tests
        CompileGatekeeper.reset()
    }

    @Test
    fun `compileWithDeduplication runs compilation when no duplicate exists`() = runBlocking {
        val inputs = createInputs(setOf("target1"), 1)
        val compileCalled = AtomicInteger(0)

        val result = CompileGatekeeper.compileWithDeduplication(
            inputs = inputs,
            compile = {
                compileCalled.incrementAndGet()
                createSuccessResult()
            },
            onEvent = {},
        )

        assertThat(compileCalled.get()).isEqualTo(1)
        assertThat(result.statusCode).isEqualTo(StatusCode.OK)
        assertThat(CompileGatekeeper.activeCompilations()).isZero()
    }

    @Test
    fun `compileWithDeduplication deduplicates concurrent identical requests`() = runBlocking {
        val inputs = createInputs(setOf("target1"), 1)
        val compileCalled = AtomicInteger(0)
        val startSignal = CompletableDeferred<Unit>()

        // Start first compilation that waits for signal
        val first = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs,
                compile = {
                    compileCalled.incrementAndGet()
                    startSignal.await() // Wait for signal before completing
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        // Give first compilation time to register
        delay(50)

        // Start second compilation with same inputs (should join first)
        val second = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs,
                compile = {
                    compileCalled.incrementAndGet() // Should NOT be called
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        // Give second compilation time to join
        delay(50)

        // Verify only one compilation is running
        assertThat(CompileGatekeeper.activeCompilations()).isEqualTo(1)

        // Complete first compilation
        startSignal.complete(Unit)

        // Wait for both to finish
        val result1 = first.await()
        val result2 = second.await()

        // Both should get the same result
        assertThat(result1.statusCode).isEqualTo(StatusCode.OK)
        assertThat(result2.statusCode).isEqualTo(StatusCode.OK)

        // Only one actual compilation should have run
        assertThat(compileCalled.get()).isEqualTo(1)

        // No active compilations remain
        assertThat(CompileGatekeeper.activeCompilations()).isZero()
    }

    @Test
    fun `compileWithDeduplication runs separate compilations for different inputs`() = runBlocking {
        val inputs1 = createInputs(setOf("target1"), 1)
        val inputs2 = createInputs(setOf("target2"), 2)
        val compileCalled = AtomicInteger(0)

        val result1 = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs1,
                compile = {
                    compileCalled.incrementAndGet()
                    delay(50) // Simulate work
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        val result2 = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs2,
                compile = {
                    compileCalled.incrementAndGet()
                    delay(50) // Simulate work
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        result1.await()
        result2.await()

        // Both compilations should have run
        assertThat(compileCalled.get()).isEqualTo(2)
        assertThat(CompileGatekeeper.activeCompilations()).isZero()
    }

    @Test
    fun `compileWithDeduplication propagates exceptions to all subscribers`() = runBlocking {
        val inputs = createInputs(setOf("target1"), 1)
        val startSignal = CompletableDeferred<Unit>()

        val first = async {
            assertThrows<TestCompilationException> {
                CompileGatekeeper.compileWithDeduplication(
                    inputs = inputs,
                    compile = {
                        startSignal.await()
                        throw TestCompilationException("Compilation failed")
                    },
                    onEvent = {},
                )
            }
        }

        delay(50)

        val second = async {
            assertThrows<TestCompilationException> {
                CompileGatekeeper.compileWithDeduplication(
                    inputs = inputs,
                    compile = {
                        // Should NOT be called
                        createSuccessResult()
                    },
                    onEvent = {},
                )
            }
        }

        delay(50)
        startSignal.complete(Unit)

        first.await()
        second.await()

        // No active compilations remain after exception
        assertThat(CompileGatekeeper.activeCompilations()).isZero()
    }

    @Test
    fun `broadcastEvent notifies all observers`() = runBlocking {
        val inputs = createInputs(setOf("target1"), 1)
        val startSignal = CompletableDeferred<Unit>()
        val events1 = mutableListOf<CompilationEvent>()
        val events2 = mutableListOf<CompilationEvent>()

        val first = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs,
                compile = {
                    // Broadcast events during compilation
                    val taskId = ch.epfl.scala.bsp4j.TaskId("test-task")
                    CompileGatekeeper.broadcastEvent(inputs, CompilationEvent.Started(taskId))
                    CompileGatekeeper.broadcastEvent(
                        inputs,
                        CompilationEvent.Finished(taskId, ch.epfl.scala.bsp4j.StatusCode.OK),
                    )
                    startSignal.await()
                    createSuccessResult()
                },
                onEvent = { events1.add(it) },
            )
        }

        delay(50)

        val second = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs,
                compile = { createSuccessResult() }, // Should NOT be called
                onEvent = { events2.add(it) },
            )
        }

        delay(50)
        startSignal.complete(Unit)

        first.await()
        second.await()

        // Both observers should receive all events
        val taskId = ch.epfl.scala.bsp4j.TaskId("test-task")
        assertThat(events1).containsExactly(
            CompilationEvent.Started(taskId),
            CompilationEvent.Finished(taskId, ch.epfl.scala.bsp4j.StatusCode.OK),
        )
        assertThat(events2).containsExactly(
            CompilationEvent.Started(taskId),
            CompilationEvent.Finished(taskId, ch.epfl.scala.bsp4j.StatusCode.OK),
        )
    }

    @Test
    fun `broadcastEvent handles observer exceptions gracefully`() = runBlocking {
        val inputs = createInputs(setOf("target1"), 1)
        val events2 = mutableListOf<CompilationEvent>()

        val result = CompileGatekeeper.compileWithDeduplication(
            inputs = inputs,
            compile = {
                val taskId = ch.epfl.scala.bsp4j.TaskId("test-task")
                CompileGatekeeper.broadcastEvent(inputs, CompilationEvent.Started(taskId))
                createSuccessResult()
            },
            onEvent = {
                // First observer throws
                @Suppress("TooGenericExceptionThrown")
                throw RuntimeException("Observer failure")
            },
        )

        // Compilation should complete despite observer exception
        assertThat(result.statusCode).isEqualTo(StatusCode.OK)
    }

    @Test
    fun `broadcastEvent is no-op for unknown inputs`() {
        // Should not throw when no compilation is registered
        val inputs = createInputs(setOf("unknown"), 999)
        val taskId = ch.epfl.scala.bsp4j.TaskId("test-task")
        CompileGatekeeper.broadcastEvent(inputs, CompilationEvent.Started(taskId))
        // If we get here without exception, test passes
    }

    @Test
    fun `activeCompilations returns correct count`() = runBlocking {
        assertThat(CompileGatekeeper.activeCompilations()).isZero()

        val inputs1 = createInputs(setOf("target1"), 1)
        val inputs2 = createInputs(setOf("target2"), 2)
        val signal1 = CompletableDeferred<Unit>()
        val signal2 = CompletableDeferred<Unit>()

        val comp1 = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs1,
                compile = {
                    signal1.await()
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        val comp2 = async {
            CompileGatekeeper.compileWithDeduplication(
                inputs = inputs2,
                compile = {
                    signal2.await()
                    createSuccessResult()
                },
                onEvent = {},
            )
        }

        delay(50)
        assertThat(CompileGatekeeper.activeCompilations()).isEqualTo(2)

        signal1.complete(Unit)
        comp1.await()
        delay(50)
        assertThat(CompileGatekeeper.activeCompilations()).isEqualTo(1)

        signal2.complete(Unit)
        comp2.await()
        delay(50)
        assertThat(CompileGatekeeper.activeCompilations()).isZero()
    }

    @Test
    fun `different target sets create unique inputs`() {
        val inputs1 = createInputs(setOf("target1"), 1)
        val inputs2 = createInputs(setOf("target1", "target2"), 1) // Different targets
        val inputs3 = createInputs(setOf("target1"), 2) // Different hash

        assertThat(inputs1).isNotEqualTo(inputs2)
        assertThat(inputs1).isNotEqualTo(inputs3)
        assertThat(inputs2).isNotEqualTo(inputs3)
    }

    @Test
    fun `same targets and hash create equal inputs`() {
        val inputs1 = createInputs(setOf("target1", "target2"), 42)
        val inputs2 = createInputs(setOf("target1", "target2"), 42)

        assertThat(inputs1).isEqualTo(inputs2)
        assertThat(inputs1.hashCode()).isEqualTo(inputs2.hashCode())
    }

    @Test
    fun `UniqueCompileInputs toString is readable`() {
        val inputs = createInputs(setOf("target1", "target2"), 12345)
        val description = inputs.toString()

        assertThat(description).contains("targets=2")
        assertThat(description).contains("hash=12345")
    }

    // ========== Helper Methods ==========

    private fun createInputs(targets: Set<String>, hash: Long): CompileGatekeeper.UniqueCompileInputs {
        val targetIds = targets.map { BuildTargetIdentifier(it) }.toSet()
        return CompileGatekeeper.UniqueCompileInputs(targetIds, hash)
    }

    private fun createSuccessResult(): CompileResult {
        val result = CompileResult(StatusCode.OK)
        result.originId = "test"
        return result
    }

    private class TestCompilationException(message: String) : RuntimeException(message)
}
