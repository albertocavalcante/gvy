"""Kotlin build macros for Groovy LSP."""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

# kotlinx-serialization plugin enabled - versions aligned with rules_kotlin 2.2.2
# (Kotlin 2.2.21 compiler bundled in kotlinbuilder, serialization plugin 2.2.21)
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

def _kt_test_impl(
        name, visibility, srcs, deps, associates, test_class, test_packages, plugins,
        resources, data, jvm_flags, env, env_inherit, tags, size, timeout, **kwargs):
    """Implementation for kt_test symbolic macro."""
    all_plugins = _DEFAULT_PLUGINS + plugins

    # JUnit 6 Platform runtime dependencies (not directly imported in source)
    # Note: JUnit jars downloaded via http_file due to GMM cycle bugs
    runtime_deps = [
        # JUnit Platform runtime
        "//tools:junit_jupiter_engine",
        "//tools:junit_platform_launcher",
        "//tools:junit_platform_console",
        # Logging runtime - required by kotlin-logging-jvm used in many modules
        "@maven//:org_slf4j_slf4j_api",
        "@maven//:ch_qos_logback_logback_classic",
    ]

    # JUnit 6 Platform ConsoleLauncher args
    # JUnit 6 changed CLI to use subcommands: `junit execute ...`
    junit_args = [
        "execute",
        "--fail-if-no-tests",
    ]

    # Add package selectors for test discovery
    for pkg in test_packages:
        junit_args.append("--select-package=" + pkg)

    # Build kt_jvm_test kwargs
    test_kwargs = {
        "name": name,
        "srcs": srcs,
        # Compile-time test deps (actually imported in source code)
        # - kotlin_test: assertions like assertEquals, assertTrue
        # - kotlin_test_junit5: provides kotlin.test.Test typealias to JUnit5
        # - junit_jupiter_api: @Test annotation (used by kotlin.test.Test)
        # Optional deps NOT included (add explicitly if needed):
        # - assertj_core: only ~10 modules use AssertJ
        # - junit_jupiter_params: only ~4 modules use @ParameterizedTest
        "deps": deps + [
            "@maven//:org_jetbrains_kotlin_kotlin_test",
            "@maven//:org_jetbrains_kotlin_kotlin_test_junit5",
            "//tools:junit_jupiter_api",
        ],
        "runtime_deps": runtime_deps,
        "associates": associates,
        "main_class": "org.junit.platform.console.ConsoleLauncher",
        "args": junit_args,
        "visibility": visibility,
        "plugins": all_plugins,
        "resources": resources,
        "data": data,
        "jvm_flags": jvm_flags,
        "env": env,
        "env_inherit": env_inherit,
        "tags": tags,
        "size": size,
    }

    # Only add timeout if specified
    if timeout:
        test_kwargs["timeout"] = timeout

    # Use JUnit Platform ConsoleLauncher for test discovery
    kt_jvm_test(**test_kwargs)

kt_test = macro(
    doc = """Kotlin test with JUnit 6 (Jupiter) and automatic test discovery.

    Provides:
    - kotlin.test assertions (assertEquals, assertTrue, etc.)
    - JUnit Jupiter @Test annotation
    - JUnit Platform ConsoleLauncher for automatic test discovery
    - kotlinx-serialization plugin enabled by default

    Optional deps (add explicitly if needed):
    - @maven//:org_assertj_assertj_core - AssertJ assertions
    - @maven//:org_junit_jupiter_junit_jupiter_params - @ParameterizedTest

    Tests are discovered via --select-package for each package in test_packages.
    The test_packages attribute is REQUIRED to specify which packages to scan.
    """,
    implementation = _kt_test_impl,
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
            doc = "Ignored - test discovery uses test_packages instead",
        ),
        "test_packages": attr.string_list(
            doc = "Java packages to scan for tests (REQUIRED). Use base package to include subpackages.",
            mandatory = True,
            configurable = False,
        ),
        "plugins": attr.label_list(
            doc = "Additional compiler plugins (serialization included by default)",
            default = [],
        ),
        "resources": attr.label_list(
            doc = "Resource files to include in the test JAR",
            allow_files = True,
            default = [],
        ),
        "data": attr.label_list(
            doc = "Data files available at runtime",
            allow_files = True,
            default = [],
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags to pass to the test runner",
            default = [],
        ),
        "env": attr.string_dict(
            doc = "Environment variables to set for the test",
            default = {},
        ),
        "env_inherit": attr.string_list(
            doc = "Environment variables to inherit from the external environment",
            default = [],
        ),
        "tags": attr.string_list(
            doc = "Tags for the test target",
            default = [],
            configurable = False,
        ),
        "size": attr.string(
            doc = "Test size (small, medium, large, enormous)",
            default = "medium",
            configurable = False,
        ),
        "timeout": attr.string(
            doc = "Test timeout (short, moderate, long, eternal)",
            configurable = False,
        ),
    },
)
