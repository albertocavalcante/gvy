// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import io.mockk.every
import io.mockk.mockk
import org.eclipse.aether.transfer.TransferCancelledException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertFailsWith

class SourceTransferBudgetTest {
    @Test
    fun `metadata substring in artifact path does not classify source jar as metadata`() {
        val budget = SourceTransferBudget(
            maxOperationBytes = 6_000_000,
            maxSourceBytes = 6_000_000,
            maxMetadataBytes = 10,
            maxChecksumBytes = 100,
        )
        val bytes = AtomicLong()
        val event = transferEvent("https://repo.test/maven-metadata/lib/lib-1-sources.jar", bytes)
        budget.transferInitiated(event)
        bytes.set(5_000_000)
        budget.transferProgressed(event)
        budget.ensureWithinBudget()
    }

    @Test
    fun `metadata filename uses metadata budget`() {
        val budget = SourceTransferBudget(100, 100, 10, 100)
        val bytes = AtomicLong()
        val event = transferEvent("https://repo.test/lib/maven-metadata.xml", bytes)
        budget.transferInitiated(event)
        bytes.set(11)
        assertFailsWith<TransferCancelledException> { budget.transferProgressed(event) }
    }

    @Test
    fun `retry transfer resource is cumulative even when Aether recreates the resource`() {
        val budget = SourceTransferBudget(
            maxOperationBytes = 100,
            maxSourceBytes = 100,
            maxMetadataBytes = 100,
            maxChecksumBytes = 100,
        )
        val firstBytes = AtomicLong()
        val firstEvent = transferEvent("https://repo.test/library-1-sources.jar", firstBytes)
        budget.transferInitiated(firstEvent)
        firstBytes.set(60)
        budget.transferProgressed(firstEvent)
        budget.transferFailed(firstEvent)

        val retryBytes = AtomicLong()
        val retryEvent = transferEvent("https://repo.test/library-1-sources.jar", retryBytes)
        budget.transferInitiated(retryEvent)
        retryBytes.set(50)

        assertFailsWith<TransferCancelledException> { budget.transferProgressed(retryEvent) }
        assertFailsWith<SourceResolverCacheLimit> { budget.ensureWithinBudget() }
    }

    private fun transferEvent(resourceName: String, transferredBytes: AtomicLong): TransferEvent {
        val resource = mockk<TransferResource>()
        every { resource.resourceName } returns resourceName
        val event = mockk<TransferEvent>()
        every { event.resource } returns resource
        every { event.transferredBytes } answers { transferredBytes.get() }
        return event
    }
}
