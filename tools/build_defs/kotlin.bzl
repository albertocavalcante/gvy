"""Kotlin build macros for Groovy LSP."""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

def kt_library(
        name,
        srcs,
        deps = None,
        runtime_deps = None,
        visibility = None,
        **kwargs):
    """Kotlin library with project defaults.

    Args:
        name: Target name
        srcs: Source files (REQUIRED - no default glob)
        deps: Compile dependencies
        runtime_deps: Runtime-only deps
        visibility: Visibility (defaults to package-private)
        **kwargs: Additional args to kt_jvm_library
    """
    kt_jvm_library(
        name = name,
        srcs = srcs,
        deps = deps if deps != None else [],
        runtime_deps = runtime_deps if runtime_deps != None else [],
        visibility = visibility,
        **kwargs
    )

def kt_test(
        name,
        srcs,
        deps = None,
        associates = None,
        test_class = None,
        **kwargs):
    """Kotlin test with JUnit 5.

    Args:
        name: Target name
        srcs: Test source files (REQUIRED - no default glob)
        deps: Dependencies (JUnit 5 added automatically)
        associates: Associated kt_library targets whose internal members are accessible
        test_class: Main test class
        **kwargs: Additional args
    """
    kt_jvm_test(
        name = name,
        srcs = srcs,
        deps = (deps if deps != None else []) + [
            "@maven//:org_jetbrains_kotlin_kotlin_test",
            "@maven//:org_jetbrains_kotlin_kotlin_test_junit5",
            "@maven//:org_junit_jupiter_junit_jupiter",
            "@maven//:org_junit_jupiter_junit_jupiter_api",
            "@maven//:org_assertj_assertj_core",
        ],
        runtime_deps = [
            "@maven//:org_junit_jupiter_junit_jupiter_engine",
            "@maven//:org_junit_platform_junit_platform_launcher",
        ],
        associates = associates if associates != None else [],
        test_class = test_class,
        **kwargs
    )
