// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import java.io.IOException
import java.nio.file.Path

/** The compiler VM's boot image, observed metadata rather than immutable module bytes. */
data class NativeJdkIdentity(
    val imageHome: Path,
    val runtimeVersion: String,
    val vendor: String,
    val modulesStamp: NativeArtifactStamp,
) {
    companion object {
        fun observe(checkCancelled: () -> Unit = {}): NativeJdkIdentity? = try {
            val home = System.getProperty("java.home")?.takeIf(String::isNotBlank)
            val version = Runtime.version().toString()
            val vendor = System.getProperty("java.vendor")?.takeIf(String::isNotBlank)
            if (home == null || vendor == null) {
                null
            } else {
                checkCancelled()
                val image = Path.of(home).toRealPath()
                NativeArtifactStamp.observe(image.resolve("lib/modules"), maxPaths = 1, checkCancelled = checkCancelled)
                    ?.takeIf { it.files == null }
                    ?.let { NativeJdkIdentity(image, version, vendor, it) }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
