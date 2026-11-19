// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceResolverDiskBudgetTest {
    @TempDir lateinit var root: Path

    @Test
    fun `queued writers recheck persisted capacity after a failed bounded transfer releases its lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        try {
            val first = worker.submit {
                withSourceResolverCapacity(root, 16, 12, 1) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    Files.writeString(root.resolve("download.part"), "12345678")
                    error("cancelled download")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = worker.submit<String> { withSourceResolverCapacity(root, 16, 12, 1) { "wrongly admitted" } }
            release.countDown()
            assertFailsWith<java.util.concurrent.ExecutionException> { first.get(5, TimeUnit.SECONDS) }
            val refused = assertFailsWith<java.util.concurrent.ExecutionException> { second.get(5, TimeUnit.SECONDS) }
            assertTrue(refused.cause is SourceResolverCacheLimit)
            assertEquals("available", withSourceResolverCapacity(root, 16, 4, 1) { "available" })
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `stored files count toward capacity without deleting an older download`() {
        val retained = Files.writeString(root.resolve("retained"), "12345678")
        assertFailsWith<SourceResolverCacheLimit> { withSourceResolverCapacity(root, 16, 12, 1) {} }
        assertEquals("12345678", Files.readString(retained))
    }
}
