package com.github.albertocavalcante.groovycommon.functional

import arrow.core.flatMap
import arrow.core.getOrElse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainResultTest {

    @Test
    fun `success wraps value in Right`() {
        val result: DomainResult<Int> = 42.success()

        assertTrue(result.isRight())
        assertEquals(42, result.getOrElse { -1 })
    }

    @Test
    fun `failure creates Left with error`() {
        val result: DomainResult<String> = "Something went wrong".failure("TestSource")

        assertTrue(result.isLeft())
        result.fold(
            ifLeft = { error ->
                assertEquals("Something went wrong", error.reason)
                assertEquals("TestSource", error.source)
                assertNull(error.cause)
            },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }

    @Test
    fun `failure with cause includes exception`() {
        val cause = RuntimeException("Root cause")
        val result: DomainResult<String> = "Wrapped error".failure("TestSource", cause)

        result.fold(
            ifLeft = { error ->
                assertEquals("Wrapped error", error.reason)
                assertEquals(cause, error.cause)
            },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }

    @Test
    fun `map transforms success value`() {
        val result = 10.success()
            .map { it * 2 }

        assertEquals(20, result.getOrElse { -1 })
    }

    @Test
    fun `map does not transform failure`() {
        val result: DomainResult<Int> = "error".failure()
        val mapped = result.map { it * 2 }

        assertTrue(mapped.isLeft())
    }

    @Test
    fun `flatMap chains operations`() {
        fun divide(a: Int, b: Int): DomainResult<Int> = if (b == 0) {
            "Division by zero".failure("Math")
        } else {
            (a / b).success()
        }

        val result = 10.success()
            .flatMap { divide(it, 2) }

        assertEquals(5, result.getOrElse { -1 })
    }

    @Test
    fun `flatMap short-circuits on failure`() {
        fun divide(a: Int, b: Int): DomainResult<Int> = if (b == 0) {
            "Division by zero".failure("Math")
        } else {
            (a / b).success()
        }

        val result = 10.success()
            .flatMap { divide(it, 0) }
            .flatMap { divide(it, 2) } // Should not be called

        assertTrue(result.isLeft())
        result.fold(
            ifLeft = { assertEquals("Division by zero", it.reason) },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }
}

class PipelineTest {

    @Test
    fun `firstSuccess returns first successful result`() = runTest {
        val pipeline = Pipeline.firstSuccess(
            Pipeline<Unit, String> { "first error".failure("A") },
            Pipeline<Unit, String> { "success".success() },
            Pipeline<Unit, String> { throw AssertionError("Should not be called") },
        )

        val result = pipeline.execute(Unit)
        assertEquals("success", result.getOrElse { "failed" })
    }

    @Test
    fun `firstSuccess returns last error if all fail`() = runTest {
        val pipeline = Pipeline.firstSuccess(
            Pipeline<Unit, String> { "error A".failure("A") },
            Pipeline<Unit, String> { "error B".failure("B") },
            Pipeline<Unit, String> { "error C".failure("C") },
        )

        val result = pipeline.execute(Unit)
        assertTrue(result.isLeft())
        result.fold(
            ifLeft = { assertEquals("error C", it.reason) },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }

    @Test
    fun `firstSuccess handles empty list`() = runTest {
        val pipeline = Pipeline.firstSuccess<Unit, String>(emptyList())

        val result = pipeline.execute(Unit)
        assertTrue(result.isLeft())
        result.fold(
            ifLeft = { assertEquals("No pipelines provided", it.reason) },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }

    @Test
    fun `firstSuccess catches exceptions and continues`() = runTest {
        val pipeline = Pipeline.firstSuccess(
            Pipeline<Unit, String> { throw IllegalStateException("Boom!") },
            Pipeline<Unit, String> { "recovered".success() },
        )

        val result = pipeline.execute(Unit)
        assertEquals("recovered", result.getOrElse { "failed" })
    }

    @Test
    fun `constant always succeeds with value`() = runTest {
        val pipeline = Pipeline.constant<Unit, Int>(42)

        val result = pipeline.execute(Unit)
        assertEquals(42, result.getOrElse { -1 })
    }

    @Test
    fun `fail always returns error`() = runTest {
        val pipeline = Pipeline.fail<Unit, String>("Always fails", "FailPipeline")

        val result = pipeline.execute(Unit)
        assertTrue(result.isLeft())
        result.fold(
            ifLeft = {
                assertEquals("Always fails", it.reason)
                assertEquals("FailPipeline", it.source)
            },
            ifRight = { throw AssertionError("Expected Left") },
        )
    }

    @Test
    fun `pipeline passes context to stages`() = runTest {
        data class Context(val multiplier: Int)

        val pipeline = Pipeline<Context, Int> { ctx ->
            (10 * ctx.multiplier).success()
        }

        val result = pipeline.execute(Context(multiplier = 5))
        assertEquals(50, result.getOrElse { -1 })
    }
}
