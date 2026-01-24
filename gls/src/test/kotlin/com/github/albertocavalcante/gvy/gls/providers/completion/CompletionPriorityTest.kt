package com.github.albertocavalcante.gvy.gls.providers.completion

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionPriorityTest {

    @Nested
    inner class PriorityOrderingTests {
        @Test
        fun `local variables have highest priority`() {
            assertEquals(0, CompletionPriority.LOCAL_VARIABLE)
        }

        @Test
        fun `parameters have second highest priority`() {
            assertEquals(1, CompletionPriority.PARAMETER)
        }

        @Test
        fun `fields come after parameters`() {
            assertTrue(CompletionPriority.FIELD > CompletionPriority.PARAMETER)
        }

        @Test
        fun `methods come after fields`() {
            assertTrue(CompletionPriority.METHOD > CompletionPriority.FIELD)
        }

        @Test
        fun `keywords have lowest priority`() {
            assertTrue(CompletionPriority.KEYWORD > CompletionPriority.JENKINS_GLOBAL)
        }

        @Test
        fun `all priorities are distinct`() {
            val priorities = listOf(
                CompletionPriority.LOCAL_VARIABLE,
                CompletionPriority.PARAMETER,
                CompletionPriority.FIELD,
                CompletionPriority.METHOD,
                CompletionPriority.MAP_KEY,
                CompletionPriority.GDK_METHOD,
                CompletionPriority.CLASSPATH_METHOD,
                CompletionPriority.IMPORTED_CLASS,
                CompletionPriority.WORKSPACE_CLASS,
                CompletionPriority.CLASSPATH_CLASS,
                CompletionPriority.JENKINS_STEP,
                CompletionPriority.JENKINS_GLOBAL,
                CompletionPriority.KEYWORD,
            )
            assertEquals(priorities.size, priorities.toSet().size, "All priorities should be unique")
        }
    }

    @Nested
    inner class SortTextTests {
        @Test
        fun `sortText formats with zero-padded priority`() {
            assertEquals("00-myVar", CompletionPriority.sortText(0, "myVar"))
            assertEquals("05-method", CompletionPriority.sortText(5, "method"))
            assertEquals("12-abstract", CompletionPriority.sortText(12, "abstract"))
        }

        @Test
        fun `sortText produces correct lexicographic order`() {
            val items = listOf(
                CompletionPriority.sortText(CompletionPriority.KEYWORD, "abstract"),
                CompletionPriority.sortText(CompletionPriority.LOCAL_VARIABLE, "myVar"),
                CompletionPriority.sortText(CompletionPriority.METHOD, "foo"),
                CompletionPriority.sortText(CompletionPriority.FIELD, "bar"),
            )

            val sorted = items.sorted()

            assertEquals("00-myVar", sorted[0], "Local variable should sort first")
            assertEquals("02-bar", sorted[1], "Field should sort second")
            assertEquals("03-foo", sorted[2], "Method should sort third")
            assertEquals("12-abstract", sorted[3], "Keyword should sort last")
        }

        @Test
        fun `sortText handles same priority with alphabetic secondary sort`() {
            val items = listOf(
                CompletionPriority.sortText(CompletionPriority.METHOD, "zebra"),
                CompletionPriority.sortText(CompletionPriority.METHOD, "apple"),
                CompletionPriority.sortText(CompletionPriority.METHOD, "mango"),
            )

            val sorted = items.sorted()

            assertEquals("03-apple", sorted[0])
            assertEquals("03-mango", sorted[1])
            assertEquals("03-zebra", sorted[2])
        }
    }
}
