// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.CliktError

/** Writes command data verbatim as UTF-8 without replacing or closing process streams. */
internal fun writeUtf8Stdout(text: String) {
    val stdout = System.out
    stdout.write(text.toByteArray(Charsets.UTF_8))
    stdout.flush()
    if (stdout.checkError()) {
        throw CliktError("Failed to write output to stdout")
    }
}
