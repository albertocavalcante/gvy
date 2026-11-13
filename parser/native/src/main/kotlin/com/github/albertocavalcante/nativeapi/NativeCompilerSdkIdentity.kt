// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import java.net.URI

/** Actual trusted compiler fallback selection. Metadata only; no classloader or immutable-byte guarantee. */
data class NativeCompilerSdkIdentity(
    val componentId: String,
    val container: URI,
    val containerStamp: NativeArtifactStamp,
    val indexDigest: String,
)
