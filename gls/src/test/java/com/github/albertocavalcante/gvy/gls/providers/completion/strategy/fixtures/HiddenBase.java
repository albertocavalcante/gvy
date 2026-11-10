package com.github.albertocavalcante.gvy.gls.providers.completion.strategy.fixtures;

/** Package-private: javac gives {@link VisibleSub} a visibility bridge for {@link #foo(int)}. */
abstract class HiddenBase {
    public int foo(int value) {
        return value;
    }
}
