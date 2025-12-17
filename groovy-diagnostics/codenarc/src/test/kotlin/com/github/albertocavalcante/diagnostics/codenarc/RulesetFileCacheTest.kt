package com.github.albertocavalcante.diagnostics.codenarc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesetFileCacheTest {

    @TempDir
    lateinit var cacheDir: Path

    @Test
    fun `same ruleset content reuses cached file path`() {
        val cache = RulesetFileCache(cacheDir)

        val rulesetContent = "ruleset { TrailingWhitespace }"
        val first = cache.getOrCreate(rulesetContent)
        val second = cache.getOrCreate(rulesetContent)

        assertEquals(first, second)
        assertTrue(Files.exists(first))
    }

    @Test
    fun `different ruleset content uses different cached file paths`() {
        val cache = RulesetFileCache(cacheDir)

        val first = cache.getOrCreate("ruleset { TrailingWhitespace }")
        val second = cache.getOrCreate("ruleset { EmptyClass }")

        assertNotEquals(first, second)
        assertTrue(Files.exists(first))
        assertTrue(Files.exists(second))
    }

    @Test
    fun `cached ruleset file contains the original content`() {
        val cache = RulesetFileCache(cacheDir)

        val rulesetContent = "ruleset { TrailingWhitespace }"
        val path = cache.getOrCreate(rulesetContent)

        val stored = Files.readString(path)
        assertNotNull(stored)
        assertEquals(rulesetContent, stored)
    }

    @Test
    fun `concurrent caches handle file creation races`() {
        val cache1 = RulesetFileCache(cacheDir)
        val cache2 = RulesetFileCache(cacheDir)

        val rulesetContent = "ruleset { TrailingWhitespace }"

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit<Path> {
                    barrier.await(5, TimeUnit.SECONDS)
                    cache1.getOrCreate(rulesetContent)
                },
                executor.submit<Path> {
                    barrier.await(5, TimeUnit.SECONDS)
                    cache2.getOrCreate(rulesetContent)
                },
            )

            val first = futures[0].get(10, TimeUnit.SECONDS)
            val second = futures[1].get(10, TimeUnit.SECONDS)

            assertEquals(first, second)
            assertTrue(Files.exists(first))

            Files.newDirectoryStream(cacheDir).use { entries ->
                for (entry in entries) {
                    assertTrue(!entry.fileName.toString().endsWith(".tmp"), "No temp files should remain")
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
