"""Kotlin build macros for Groovy LSP."""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

# kotlinx-serialization plugin enabled - versions now aligned with rules_kotlin 2.2.2
# (Kotlin 2.2.21 compiler, serialization plugin 2.2.21)
_DEFAULT_PLUGINS = ["//tools/build_defs:serialization_plugin"]

def _kt_library_impl(name, visibility, srcs, deps, runtime_deps, plugins, **kwargs):
    """Implementation for kt_library symbolic macro."""
    all_plugins = _DEFAULT_PLUGINS + plugins
    kt_jvm_library(
        name = name,
        srcs = srcs,
        deps = deps,
        runtime_deps = runtime_deps,
        visibility = visibility,
        plugins = all_plugins,
        **kwargs
    )

kt_library = macro(
    doc = """Kotlin library with project defaults.

    Provides:
    - kotlinx-serialization plugin enabled by default
    - Typed attributes with validation
    - Better error messages through symbolic macro
    """,
    implementation = _kt_library_impl,
    inherit_attrs = kt_jvm_library,
    attrs = {
        "srcs": attr.label_list(
            doc = "Kotlin source files (REQUIRED - no default glob)",
            allow_files = [".kt"],
            mandatory = True,
        ),
        "deps": attr.label_list(
            doc = "Compile dependencies",
            default = [],
        ),
        "runtime_deps": attr.label_list(
            doc = "Runtime-only dependencies",
            default = [],
        ),
        "plugins": attr.label_list(
            doc = "Additional compiler plugins (serialization included by default)",
            default = [],
        ),
    },
)

def _kt_test_impl(name, visibility, srcs, deps, associates, test_class, plugins, **kwargs):
    """Implementation for kt_test symbolic macro."""
    all_plugins = _DEFAULT_PLUGINS + plugins

    # Merge runtime_deps from kwargs with our defaults
    existing_runtime_deps = kwargs.pop("runtime_deps", None)
    if existing_runtime_deps == None:
        existing_runtime_deps = []
    runtime_deps = existing_runtime_deps + [
        "@maven//:org_junit_jupiter_junit_jupiter_engine",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ]

    kt_jvm_test(
        name = name,
        srcs = srcs,
        deps = deps + [
            "@maven//:org_jetbrains_kotlin_kotlin_test",
            "@maven//:org_jetbrains_kotlin_kotlin_test_junit5",
            "@maven//:org_junit_jupiter_junit_jupiter",
            "@maven//:org_junit_jupiter_junit_jupiter_api",
            "@maven//:org_assertj_assertj_core",
        ],
        runtime_deps = runtime_deps,
        associates = associates,
        test_class = test_class,
        visibility = visibility,
        plugins = all_plugins,
        **kwargs
    )

kt_test = macro(
    doc = """Kotlin test with JUnit 6 (Jupiter).

    Provides:
    - JUnit 6 (Jupiter) dependencies automatically included
    - AssertJ for assertions
    - kotlinx-serialization plugin enabled by default
    - Typed attributes with validation
    """,
    implementation = _kt_test_impl,
    inherit_attrs = kt_jvm_test,
    attrs = {
        "srcs": attr.label_list(
            doc = "Kotlin test source files (REQUIRED - no default glob)",
            allow_files = [".kt"],
            mandatory = True,
        ),
        "deps": attr.label_list(
            doc = "Dependencies (JUnit 6 Jupiter added automatically)",
            default = [],
        ),
        "associates": attr.label_list(
            doc = "Associated kt_library targets whose internal members are accessible",
            default = [],
        ),
        "test_class": attr.string(
            doc = "Main test class (optional)",
        ),
        "plugins": attr.label_list(
            doc = "Additional compiler plugins (serialization included by default)",
            default = [],
        ),
    },
)
