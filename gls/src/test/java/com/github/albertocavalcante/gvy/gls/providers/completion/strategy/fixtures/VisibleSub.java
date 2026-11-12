// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion.strategy.fixtures;

/** Exposes the inherited {@code foo(int)} only as a bridge, next to its own unrelated overload. */
public class VisibleSub extends HiddenBase {
    public int foo(String value) {
        return value.length();
    }
}
