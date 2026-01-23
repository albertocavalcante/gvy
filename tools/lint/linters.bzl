"""Linter aspect definitions for the Groovy Language Server project."""

load("@aspect_rules_lint//lint:eslint.bzl", "lint_eslint_aspect")
load("@aspect_rules_lint//lint:ktlint.bzl", "lint_ktlint_aspect")
load("@aspect_rules_lint//lint:buf.bzl", "lint_buf_aspect")

# ESLint aspect for TypeScript/JavaScript linting
eslint = lint_eslint_aspect(
    binary = Label("//tools/lint:eslint"),
    configs = [
        Label("//editors/code:eslint.config.mjs"),
    ],
)

# ktlint aspect for Kotlin linting with editorconfig
ktlint = lint_ktlint_aspect(
    binary = Label("//tools/lint:ktlint"),
    editorconfig = Label("//:.editorconfig"),
)

# buf aspect for Protocol Buffers linting
buf = lint_buf_aspect(
    binary = Label("//tools/lint:buf"),
)
