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
