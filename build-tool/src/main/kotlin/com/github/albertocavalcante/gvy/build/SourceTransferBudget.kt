// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferCancelledException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import java.util.IdentityHashMap
import java.util.Locale

internal const val VERIFIED_SOURCE_MAX_ARTIFACT_BYTES = 64L * 1_048_576L
internal const val VERIFIED_SOURCE_MAX_METADATA_BYTES = 8L * 1_048_576L
internal const val VERIFIED_SOURCE_MAX_CHECKSUM_BYTES = 2L * 1_024L
internal const val VERIFIED_SOURCE_MAX_OPERATION_BYTES = 65L * 1_048_576L
internal const val VERIFIED_SOURCE_MAX_TRANSFER_ATTEMPTS = 8
internal const val VERIFIED_SOURCE_TRANSFER_CALLBACK_SLACK_BYTES =
    VERIFIED_SOURCE_MAX_TRANSFER_ATTEMPTS * 32L * 1_024L
internal const val VERIFIED_SOURCE_LOCAL_TRACKING_SLACK_BYTES = 64L * 1_024L
internal const val VERIFIED_SOURCE_CACHE_RESERVATION_BYTES = VERIFIED_SOURCE_MAX_OPERATION_BYTES +
    VERIFIED_SOURCE_TRANSFER_CALLBACK_SLACK_BYTES + VERIFIED_SOURCE_LOCAL_TRACKING_SLACK_BYTES

/** Counts bytes across all attempts in one resolver operation, even when a transfer restarts. */
internal class SourceTransferBudget(
    private val maxOperationBytes: Long,
    private val maxSourceBytes: Long,
    private val maxMetadataBytes: Long,
    private val maxChecksumBytes: Long,
    private val cancelTransfers: () -> Unit = {},
) : AbstractTransferListener() {
    private val attempts = IdentityHashMap<TransferResource, TransferProgress>()
    private var operationBytes = 0L
    private var sourceBytes = 0L
    private var metadataBytes = 0L
    private var checksumBytes = 0L
    private var transferAttempts = 0
    private var exceeded = false

    override fun transferInitiated(event: TransferEvent) {
        synchronized(this) {
            if (exceeded) throw TransferCancelledException("Source transfer budget exceeded")
            if (++transferAttempts > VERIFIED_SOURCE_MAX_TRANSFER_ATTEMPTS) exceedBudget(cancelTransfer = true)
            attempts[event.resource] = TransferProgress(classify(event.resource.resourceName))
        }
    }

    override fun transferProgressed(event: TransferEvent) {
        synchronized(this) { recordProgress(event) }
    }

    override fun transferSucceeded(event: TransferEvent) {
        synchronized(this) {
            recordProgress(event, cancelWhenExceeded = false)
            attempts.remove(event.resource)
        }
    }

    override fun transferFailed(event: TransferEvent) {
        synchronized(this) {
            recordProgress(event, cancelWhenExceeded = false)
            attempts.remove(event.resource)
        }
    }

    override fun transferCorrupted(event: TransferEvent) {
        synchronized(this) {
            recordProgress(event, cancelWhenExceeded = false)
            attempts.remove(event.resource)
        }
    }

    fun ensureWithinBudget() {
        if (synchronized(this) { exceeded }) throw SourceResolverCacheLimit()
    }

    private fun recordProgress(event: TransferEvent, cancelWhenExceeded: Boolean = true) {
        if (exceeded) {
            if (cancelWhenExceeded) throw TransferCancelledException("Source transfer budget exceeded")
            return
        }
        val progress = progressDelta(event)
        if (progress.delta == 0L) return
        validateOperationProgress(progress, cancelWhenExceeded)
        addTransferProgress(progress, cancelWhenExceeded)
        operationBytes += progress.delta
    }

    private fun progressDelta(event: TransferEvent): TransferDelta {
        val resource = event.resource
        val previous = attempts[resource] ?: TransferProgress(classify(resource.resourceName))
        val currentBytes = event.transferredBytes
        val delta = if (currentBytes < previous.lastBytes) currentBytes else currentBytes - previous.lastBytes
        attempts[resource] = previous.copy(lastBytes = currentBytes)
        return TransferDelta(previous.kind, currentBytes, delta)
    }

    private fun validateOperationProgress(progress: TransferDelta, cancelWhenExceeded: Boolean) {
        if (
            progress.currentBytes > progress.kind.maxTransferBytes ||
            !canAdd(operationBytes, progress.delta, maxOperationBytes)
        ) {
            exceedBudget(cancelWhenExceeded)
        }
    }

    private fun addTransferProgress(progress: TransferDelta, cancelWhenExceeded: Boolean) {
        when (progress.kind) {
            TransferKind.SOURCE ->
                sourceBytes =
                    addWithinLimit(sourceBytes, progress.delta, maxSourceBytes, cancelWhenExceeded)
            TransferKind.METADATA ->
                metadataBytes =
                    addWithinLimit(metadataBytes, progress.delta, maxMetadataBytes, cancelWhenExceeded)
            TransferKind.CHECKSUM ->
                checksumBytes =
                    addWithinLimit(checksumBytes, progress.delta, maxChecksumBytes, cancelWhenExceeded)
            TransferKind.UNKNOWN -> exceedBudget(cancelWhenExceeded)
        }
    }

    private fun addWithinLimit(current: Long, delta: Long, maximum: Long, cancelWhenExceeded: Boolean): Long {
        if (!canAdd(current, delta, maximum)) exceedBudget(cancelWhenExceeded)
        return current + delta
    }

    private fun classify(resourceName: String): TransferKind {
        val path = resourceName.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".sha256") -> TransferKind.CHECKSUM
            path.substringAfterLast('/') == "maven-metadata.xml" -> TransferKind.METADATA
            path.endsWith("-sources.jar") -> TransferKind.SOURCE
            else -> TransferKind.UNKNOWN
        }
    }

    private fun canAdd(current: Long, delta: Long, maximum: Long): Boolean = delta >= 0 && delta <= maximum - current

    private fun exceedBudget(cancelTransfer: Boolean) {
        exceeded = true
        cancelTransfers()
        if (cancelTransfer) throw TransferCancelledException("Source transfer budget exceeded")
    }

    private data class TransferProgress(val kind: TransferKind, val lastBytes: Long = 0)

    private data class TransferDelta(val kind: TransferKind, val currentBytes: Long, val delta: Long)

    private enum class TransferKind(val maxTransferBytes: Long) {
        SOURCE(VERIFIED_SOURCE_MAX_ARTIFACT_BYTES),
        METADATA(MAX_METADATA_BYTES_PER_TRANSFER),
        CHECKSUM(MAX_CHECKSUM_BYTES_PER_TRANSFER),
        UNKNOWN(0),
    }

    private companion object {
        const val MAX_METADATA_BYTES_PER_TRANSFER = 4L * 1_048_576L
        const val MAX_CHECKSUM_BYTES_PER_TRANSFER = 1_024L
    }
}
