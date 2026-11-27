// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.util.HexFormat

/**
 * A SHA-256 digest with value semantics: two instances are equal when their 32 bytes are equal. The bytes are
 * copied on the way in and on the way out, so an instance never changes.
 */
public class Sha256 private constructor(private val bytes: ByteArray) {
    /** Returns a copy of the 32 digest bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is Sha256 && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Renders the digest as 64 lowercase hexadecimal digits. */
    override fun toString(): String = HexFormat.of().formatHex(bytes)

    public companion object {
        /** The size of a SHA-256 digest in bytes. */
        public const val SIZE_BYTES: Int = 32

        /** Copies [bytes], which must hold exactly [SIZE_BYTES] bytes. */
        public fun of(bytes: ByteArray): Sha256 {
            require(bytes.size == SIZE_BYTES) { "A SHA-256 digest has $SIZE_BYTES bytes, got ${bytes.size}" }
            return Sha256(bytes.copyOf())
        }
    }
}
