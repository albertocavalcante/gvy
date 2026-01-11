"""Kotlin build macros for Groovy LSP."""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

# Note: kotlinx-serialization plugin is disabled due to version mismatch with
# rules_kotlin's internal Kotlin compiler. Modules using @Serializable need to
# either use Jackson/Gson or wait for rules_kotlin version alignment.
_DEFAULT_PLUGINS = []

def kt_library(
        name,
        srcs,
        deps = None,
        runtime_deps = None,
        visibility = None,
        plugins = None,
        **kwargs):
    """Kotlin library with project defaults.

    Args:
        name: Target name
        srcs: Source files (REQUIRED - no default glob)
        deps: Compile dependencies
        runtime_deps: Runtime-only deps
        visibility: Visibility (defaults to package-private)
        plugins: Additional compiler plugins (serialization included by default)
        **kwargs: Additional args to kt_jvm_library
    """
    all_plugins = _DEFAULT_PLUGINS + (plugins if plugins != None else [])
    kt_jvm_library(
        name = name,
        srcs = srcs,
        deps = deps if deps != None else [],
        runtime_deps = runtime_deps if runtime_deps != None else [],
        visibility = visibility,
        plugins = all_plugins,
        **kwargs
    )

def kt_test(
        name,
        srcs,
        deps = None,
        associates = None,
        test_class = None,
        plugins = None,
        **kwargs):
    """Kotlin test with JUnit 6 (Jupiter).

    Args:
        name: Target name
        srcs: Test source files (REQUIRED - no default glob)
        deps: Dependencies (JUnit 6 Jupiter added automatically)
        associates: Associated kt_library targets whose internal members are accessible
        test_class: Main test class
        plugins: Additional compiler plugins (serialization included by default)
        **kwargs: Additional args
    """
    all_plugins = _DEFAULT_PLUGINS + (plugins if plugins != None else [])
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
        plugins = all_plugins,
        **kwargs
    )
