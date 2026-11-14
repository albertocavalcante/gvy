// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Bound central-directory allocation before ZipFile constructs its in-memory table. */
internal object ZipDirectoryValidator {
    private const val ZIP_EOCD_LENGTH = 22
    private const val MAX_ZIP_COMMENT_BYTES = 65_535
    private const val ZIP_EOCD_SIGNATURE = 0x06054b50
    private const val ZIP_DISK_NUMBER_OFFSET = 4
    private const val ZIP_CENTRAL_DIRECTORY_DISK_OFFSET = 6
    private const val ZIP_DISK_ENTRY_COUNT_OFFSET = 8
    private const val ZIP_TOTAL_ENTRY_COUNT_OFFSET = 10
    private const val ZIP_CENTRAL_DIRECTORY_SIZE_OFFSET = 12
    private const val ZIP_CENTRAL_DIRECTORY_OFFSET = 16
    private const val ZIP_COMMENT_LENGTH_OFFSET = 20
    private const val ZIP_UNSIGNED_SHORT_MASK = 0xffff
    private const val ZIP_UNSIGNED_INTEGER_MASK = 0xffff_ffffL
    private const val ZIP_CENTRAL_HEADER_LENGTH = 46
    private const val ZIP_CENTRAL_HEADER_SIGNATURE = 0x02014b50
    private const val ZIP_CENTRAL_NAME_LENGTH_OFFSET = 28
    private const val ZIP_CENTRAL_EXTRA_LENGTH_OFFSET = 30
    private const val ZIP_CENTRAL_COMMENT_LENGTH_OFFSET = 32

    private data class DirectoryRecord(
        val recordOffset: Int,
        val disk: Int,
        val centralDisk: Int,
        val diskEntries: Int,
        val entries: Int,
        val centralBytes: Long,
        val centralOffset: Long,
    )

    fun validate(path: Path, limits: SourceStoreLimits) {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            ensureMinimumLength(size)
            val tail = readTail(channel, size)
            val record = findRecord(tail) ?: throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            validateRecord(record, size, tail.limit(), limits)
            countEntries(channel, record, size - tail.limit() + record.recordOffset, limits)
        }
    }

    private fun ensureMinimumLength(size: Long) {
        if (size < ZIP_EOCD_LENGTH) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    }

    private fun readTail(channel: FileChannel, size: Long): ByteBuffer {
        val count = minOf(size, (ZIP_EOCD_LENGTH + MAX_ZIP_COMMENT_BYTES).toLong()).toInt()
        val tail = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        var position = size - count
        while (tail.hasRemaining()) {
            val read = channel.read(tail, position)
            if (read < 0) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            position += read
        }
        return tail
    }

    private fun findRecord(tail: ByteBuffer): DirectoryRecord? =
        (tail.limit() - ZIP_EOCD_LENGTH downTo 0).firstNotNullOfOrNull { offset -> parseRecord(tail, offset) }

    private fun parseRecord(tail: ByteBuffer, offset: Int): DirectoryRecord? {
        if (tail.getInt(offset) != ZIP_EOCD_SIGNATURE) return null
        val commentLength = tail.getShort(offset + ZIP_COMMENT_LENGTH_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK
        if (offset + ZIP_EOCD_LENGTH + commentLength != tail.limit()) return null
        return DirectoryRecord(
            recordOffset = offset,
            disk = tail.getShort(offset + ZIP_DISK_NUMBER_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK,
            centralDisk = tail.getShort(offset + ZIP_CENTRAL_DIRECTORY_DISK_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK,
            diskEntries = tail.getShort(offset + ZIP_DISK_ENTRY_COUNT_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK,
            entries = tail.getShort(offset + ZIP_TOTAL_ENTRY_COUNT_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK,
            centralBytes =
            tail.getInt(offset + ZIP_CENTRAL_DIRECTORY_SIZE_OFFSET).toLong() and ZIP_UNSIGNED_INTEGER_MASK,
            centralOffset = tail.getInt(offset + ZIP_CENTRAL_DIRECTORY_OFFSET).toLong() and ZIP_UNSIGNED_INTEGER_MASK,
        )
    }

    private fun validateRecord(record: DirectoryRecord, size: Long, tailBytes: Int, limits: SourceStoreLimits) {
        validateSingleDisk(record)
        validateDirectorySize(record, limits)
        validateDirectoryLocation(record, size - tailBytes)
    }

    private fun validateSingleDisk(record: DirectoryRecord) {
        if (record.disk != 0 || record.centralDisk != 0 || record.diskEntries != record.entries) {
            throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        }
    }

    private fun validateDirectorySize(record: DirectoryRecord, limits: SourceStoreLimits) {
        if (record.entries > limits.maxArchiveEntries || record.centralBytes > limits.maxCentralDirectoryBytes) {
            throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
        }
    }

    private fun validateDirectoryLocation(record: DirectoryRecord, tailStart: Long) {
        if (record.centralOffset + record.centralBytes > tailStart + record.recordOffset) {
            throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        }
    }

    private fun countEntries(
        channel: FileChannel,
        record: DirectoryRecord,
        eocdOffset: Long,
        limits: SourceStoreLimits,
    ) {
        // ZipFile locates the table relative to the EOCD, including for prefixed archives.
        var position = eocdOffset - record.centralBytes
        val end = eocdOffset
        var count = 0
        val header = ByteBuffer.allocate(ZIP_CENTRAL_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        while (position < end) {
            checkSourceInterruption()
            readCentralHeader(channel, position, end, header)
            count++
            if (count > limits.maxArchiveEntries) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            val variableBytes =
                (header.getShort(ZIP_CENTRAL_NAME_LENGTH_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK) +
                    (header.getShort(ZIP_CENTRAL_EXTRA_LENGTH_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK) +
                    (header.getShort(ZIP_CENTRAL_COMMENT_LENGTH_OFFSET).toInt() and ZIP_UNSIGNED_SHORT_MASK)
            position += ZIP_CENTRAL_HEADER_LENGTH + variableBytes
            if (position > end) unsafeArchive()
        }
        if (count != record.entries) unsafeArchive()
    }

    private fun readCentralHeader(channel: FileChannel, position: Long, end: Long, header: ByteBuffer) {
        if (end - position < ZIP_CENTRAL_HEADER_LENGTH) unsafeArchive()
        header.clear()
        while (header.hasRemaining()) {
            val read = channel.read(header, position + header.position())
            if (read <= 0) unsafeArchive()
        }
        if (header.getInt(0) != ZIP_CENTRAL_HEADER_SIGNATURE) unsafeArchive()
    }

    private fun unsafeArchive(): Nothing = throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
}

/** Validates archive paths before source entries are indexed. */
internal object SourceArchiveNameValidator {
    private val windowsDeviceName = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

    fun validate(name: String, directory: Boolean, limits: SourceStoreLimits): String {
        if (name.length > limits.maxEntryPathCharacters) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
        val value = if (directory) name.removeSuffix("/") else name
        val segments = value.split('/')
        if (isUnsafeArchiveEntryPath(value, segments)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        return value
    }

    private fun isUnsafeArchiveEntryPath(value: String, segments: List<String>): Boolean =
        value.isEmpty() || hasUnsafePathCharacters(value) || segments.any(::isUnsafePathSegment)

    private fun hasUnsafePathCharacters(value: String): Boolean =
        value.startsWith('/') || hasWindowsPathCharacters(value)

    private fun hasWindowsPathCharacters(value: String): Boolean = '\\' in value || hasDriveOrNullCharacter(value)

    private fun hasDriveOrNullCharacter(value: String): Boolean = ':' in value || '\u0000' in value

    private fun isUnsafePathSegment(segment: String): Boolean = segment.isEmpty() || hasUnsafePathSegmentValue(segment)

    private fun hasUnsafePathSegmentValue(segment: String): Boolean =
        hasUnsafeTraversalOrSuffix(segment) || windowsDeviceName.matches(segment)

    private fun hasUnsafeTraversalOrSuffix(segment: String): Boolean =
        isTraversalSegment(segment) || hasUnsafeSegmentSuffix(segment)

    private fun isTraversalSegment(segment: String): Boolean = segment == "." || segment == ".."

    private fun hasUnsafeSegmentSuffix(segment: String): Boolean = segment.endsWith('.') || segment.endsWith(' ')
}
