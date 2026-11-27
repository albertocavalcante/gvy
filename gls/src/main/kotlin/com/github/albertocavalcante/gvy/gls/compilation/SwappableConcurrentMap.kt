// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import java.util.concurrent.ConcurrentMap

/**
 * A concurrent map that its owner changes in place and empties by swapping in a fresh one, so emptying it costs O(1)
 * however large it is (#1342).
 *
 * This is deliberately not a [SharedValue] of the map: a `SharedValue` holds values replaced as a whole, such as
 * immutable snapshots, while this map is mutable and shared. What it guarantees is only that [map] is always a whole
 * map, the current one or, for a caller that read it just before [swapEmpty], the one that call retired. A write that
 * races a swap can therefore land in the retired map and be dropped with it. Each owner says why that is harmless for
 * it: a lock that every write and the swap hold, or entries stamped with a ticket that the swap's caller retires.
 *
 * [empty] must return a new map on every call: a factory that returns a shared instance would make [swapEmpty]
 * swap a map for itself, so it would empty nothing and return the live map as the retired one.
 */
internal class SwappableConcurrentMap<M : ConcurrentMap<*, *>>(private val empty: () -> M) {
    private val current = SharedValue(empty())

    /** The live map. A caller that keeps it across a [swapEmpty] keeps using the retired map. */
    val map: M get() = current.value

    /** Swaps in an empty map and returns the one it retired, for example to act on what it held. */
    fun swapEmpty(): M = current.exchange(empty())
}
