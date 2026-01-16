package com.github.albertocavalcante.groovylsp.providers.completion.strategy

import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.CompletionItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompletionStrategyTest {

    @Test
    fun `aggregate collects completions from all applicable strategies`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val strategy1 = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "item1" }))
        }
        val strategy2 = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "item2" }))
        }

        val aggregated = CompletionStrategy.aggregate(listOf(strategy1, strategy2))

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(2)
                assertThat(items.map { it.label }).containsExactlyInAnyOrder("item1", "item2")
            },
        )
    }

    @Test
    fun `aggregate skips strategies that return notApplicable`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val applicableStrategy = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "item1" }))
        }
        val notApplicableStrategy = CompletionStrategy {
            CompletionStrategy.notApplicable("test")
        }

        val aggregated = CompletionStrategy.aggregate(listOf(notApplicableStrategy, applicableStrategy))

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(1)
                assertThat(items.first().label).isEqualTo("item1")
            },
        )
    }

    @Test
    fun `aggregate handles all strategies returning notApplicable`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val strategy1 = CompletionStrategy { CompletionStrategy.notApplicable("test1") }
        val strategy2 = CompletionStrategy { CompletionStrategy.notApplicable("test2") }

        val aggregated = CompletionStrategy.aggregate(listOf(strategy1, strategy2))

        val result = runBlocking { aggregated.complete(context) }

        // When all strategies are not applicable, aggregate returns empty list (Right)
        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items -> assertThat(items).isEmpty() },
        )
    }

    @Test
    fun `aggregate handles exceptions gracefully`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val throwingStrategy = CompletionStrategy {
            throw RuntimeException("Test exception")
        }
        val workingStrategy = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "item1" }))
        }

        val aggregated = CompletionStrategy.aggregate(listOf(throwingStrategy, workingStrategy))

        val result = runBlocking { aggregated.complete(context) }

        // Exception should be caught and treated as notApplicable
        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(1)
                assertThat(items.first().label).isEqualTo("item1")
            },
        )
    }

    @Test
    fun `aggregate propagates CancellationException`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val cancellingStrategy = CompletionStrategy {
            throw CancellationException("Cancelled")
        }

        val aggregated = CompletionStrategy.aggregate(listOf(cancellingStrategy))

        assertThrows<CancellationException> {
            runBlocking { aggregated.complete(context) }
        }
    }

    @Test
    fun `aggregate propagates Error`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val errorStrategy = CompletionStrategy {
            throw OutOfMemoryError("Test error")
        }

        val aggregated = CompletionStrategy.aggregate(listOf(errorStrategy))

        assertThrows<OutOfMemoryError> {
            runBlocking { aggregated.complete(context) }
        }
    }

    @Test
    fun `aggregate handles empty strategy list`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val aggregated = CompletionStrategy.aggregate(emptyList())

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items -> assertThat(items).isEmpty() },
        )
    }

    @Test
    fun `aggregate collects multiple items from single strategy`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val multiItemStrategy = CompletionStrategy {
            CompletionStrategy.found(
                listOf(
                    CompletionItem().apply { label = "item1" },
                    CompletionItem().apply { label = "item2" },
                    CompletionItem().apply { label = "item3" },
                ),
            )
        }

        val aggregated = CompletionStrategy.aggregate(listOf(multiItemStrategy))

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(3)
                assertThat(items.map { it.label }).containsExactly("item1", "item2", "item3")
            },
        )
    }

    @Test
    fun `aggregate maintains order of items from strategies`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val strategy1 = CompletionStrategy {
            CompletionStrategy.found(
                listOf(
                    CompletionItem().apply { label = "a1" },
                    CompletionItem().apply { label = "a2" },
                ),
            )
        }
        val strategy2 = CompletionStrategy {
            CompletionStrategy.found(
                listOf(
                    CompletionItem().apply { label = "b1" },
                    CompletionItem().apply { label = "b2" },
                ),
            )
        }

        val aggregated = CompletionStrategy.aggregate(listOf(strategy1, strategy2))

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(4)
                assertThat(items.map { it.label }).containsExactly("a1", "a2", "b1", "b2")
            },
        )
    }

    @Test
    fun `notApplicable returns Left with correct structure`() {
        val result = CompletionStrategy.notApplicable("TestStrategy")

        assertThat(result.isLeft()).isTrue()
        result.fold(
            ifLeft = { error ->
                assertThat(error.reason).isEqualTo("Strategy not applicable")
                assertThat(error.source).isEqualTo("TestStrategy")
            },
            ifRight = { throw AssertionError("Expected Left, got Right: $it") },
        )
    }

    @Test
    fun `notApplicable with default strategy name`() {
        val result = CompletionStrategy.notApplicable()

        assertThat(result.isLeft()).isTrue()
        result.fold(
            ifLeft = { error ->
                assertThat(error.reason).isEqualTo("Strategy not applicable")
                assertThat(error.source).isEqualTo("unknown")
            },
            ifRight = { throw AssertionError("Expected Left, got Right: $it") },
        )
    }

    @Test
    fun `found returns Right with items`() {
        val items = listOf(
            CompletionItem().apply { label = "item1" },
            CompletionItem().apply { label = "item2" },
        )

        val result = CompletionStrategy.found(items)

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { actualItems ->
                assertThat(actualItems).hasSize(2)
                assertThat(actualItems).isEqualTo(items)
            },
        )
    }

    @Test
    fun `found returns Right with empty list`() {
        val result = CompletionStrategy.found(emptyList())

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items -> assertThat(items).isEmpty() },
        )
    }

    @Test
    fun `error returns Left with correct structure`() {
        val result = CompletionStrategy.error("Something went wrong", "MyStrategy")

        assertThat(result.isLeft()).isTrue()
        result.fold(
            ifLeft = { error ->
                assertThat(error.reason).isEqualTo("Something went wrong")
                assertThat(error.source).isEqualTo("MyStrategy")
            },
            ifRight = { throw AssertionError("Expected Right, got Right: $it") },
        )
    }

    @Test
    fun `error with default strategy name`() {
        val result = CompletionStrategy.error("Failed")

        assertThat(result.isLeft()).isTrue()
        result.fold(
            ifLeft = { error ->
                assertThat(error.reason).isEqualTo("Failed")
                assertThat(error.source).isEqualTo("unknown")
            },
            ifRight = { throw AssertionError("Expected Right, got Right: $it") },
        )
    }

    @Test
    fun `strategy can use suspend functions`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val suspendingStrategy = CompletionStrategy { _ ->
            // Simulate async operation
            kotlinx.coroutines.delay(10)
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "async-item" }))
        }

        val result = runBlocking { suspendingStrategy.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(1)
                assertThat(items.first().label).isEqualTo("async-item")
            },
        )
    }

    @Test
    fun `aggregate handles mix of successful and error strategies`() {
        val context = mockk<CompletionStrategyContext>(relaxed = true)

        val strategy1 = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "success1" }))
        }
        val strategy2 = CompletionStrategy {
            CompletionStrategy.error("Failed", "Strategy2")
        }
        val strategy3 = CompletionStrategy {
            CompletionStrategy.found(listOf(CompletionItem().apply { label = "success2" }))
        }

        val aggregated = CompletionStrategy.aggregate(listOf(strategy1, strategy2, strategy3))

        val result = runBlocking { aggregated.complete(context) }

        assertThat(result.isRight()).isTrue()
        result.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                assertThat(items).hasSize(2)
                assertThat(items.map { it.label }).containsExactlyInAnyOrder("success1", "success2")
            },
        )
    }
}
