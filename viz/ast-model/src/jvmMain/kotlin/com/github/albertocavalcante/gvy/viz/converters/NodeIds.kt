// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.converters

/** Numbers one conversion's nodes in visit order: `node-1`, `node-2`, ... A conversion runs on one thread. */
internal class NodeIds {
    private var last = 0

    fun next(): String = "node-${++last}"
}
