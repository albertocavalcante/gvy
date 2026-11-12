// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Highest version prepared by high-level steps in each open document lifecycle; explicit wire values remain testable.
 */
internal class DocumentVersions {
    private val versions = ConcurrentHashMap<URI, Int>()

    fun opened(uri: String, version: Int) {
        versions[URI.create(uri)] = version
    }

    fun next(uri: String, explicit: Int?): Int {
        if (explicit != null) {
            versions.merge(URI.create(uri), explicit) { previous, value -> maxOf(previous, value) }
            return explicit
        }
        return checkNotNull(
            versions.compute(URI.create(uri)) { _, previous ->
                val current = checkNotNull(previous) {
                    "Automatic change version for '$uri' requires an earlier openDocument or an explicit version"
                }
                Math.incrementExact(current)
            },
        )
    }

    fun closed(uri: String) {
        versions.remove(URI.create(uri))
    }
}
