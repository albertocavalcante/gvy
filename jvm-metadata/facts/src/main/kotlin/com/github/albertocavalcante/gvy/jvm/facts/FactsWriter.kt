// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** Writes facts files. */
public object FactsWriter {
    /** Returns the bytes of a facts file for [facts]. */
    public fun write(facts: Facts, options: WriteOptions = WriteOptions()): ByteArray =
        TODO("slice 1: the writer of ${facts.header.inputKind}, ${options.strings}")
}
