"""Java build macros for Groovy LSP."""

def java_library(
        name,
        srcs = None,
        deps = None,
        runtime_deps = None,
        visibility = None,
        **kwargs):
    """Java library with project defaults.

    Args:
        name: Target name
        srcs: Source files (defaults to all .java in package)
        deps: Compile dependencies
        runtime_deps: Runtime-only deps
        visibility: Visibility
        **kwargs: Additional args to native.java_library
    """
    native.java_library(
        name = name,
        srcs = srcs if srcs else native.glob(["src/main/java/**/*.java"]),
        deps = deps or [],
        runtime_deps = runtime_deps or [],
        visibility = visibility or ["//visibility:public"],
        javacopts = [
            "-source",
            "17",
            "-target",
            "17",
        ],
        **kwargs
    )

def java_test(
        name,
        srcs = None,
        deps = None,
        test_class = None,
        **kwargs):
    """Java test with JUnit 5.

    Args:
        name: Target name
        srcs: Test source files
        deps: Dependencies (JUnit 5 added automatically)
        test_class: Main test class
        **kwargs: Additional args
    """
    native.java_test(
        name = name,
        srcs = srcs if srcs else native.glob(["src/test/java/**/*Test.java"]),
        deps = (deps or []) + [
            "@maven//:org_junit_jupiter_junit_jupiter",
            "@maven//:org_junit_jupiter_junit_jupiter_api",
            "@maven//:org_assertj_assertj_core",
        ],
        runtime_deps = [
            "@maven//:org_junit_jupiter_junit_jupiter_engine",
            "@maven//:org_junit_platform_junit_platform_launcher",
        ],
        test_class = test_class,
        javacopts = [
            "-source",
            "17",
            "-target",
            "17",
        ],
        **kwargs
    )
