"""Module extension to download Wire compiler shadow jar.

GMM (Gradle Module Metadata) causes rules_jvm_external to resolve wire-compiler
incorrectly. We download the shadow jar directly to avoid this issue.

TODO(rules_wire): Consider spinning off Wire support into a standalone ruleset.
This module extension + wire_proto_library macro could form the basis of rules_wire.
See: https://github.com/albertocavalcante/gvy/issues/1030

Tracking: https://github.com/albertocavalcante/gvy/issues/1029
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

_WIRE_VERSION = "5.4.0"
_WIRE_SHA256 = "7a42e0f7db30f330b41b76d3c5b0a0f5c9d6d3f5c7e8a9b0c1d2e3f4a5b6c7d8"  # placeholder

def _wire_compiler_impl(ctx):
    http_file(
        name = "wire_compiler_jar",
        urls = [
            "https://repo1.maven.org/maven2/com/squareup/wire/wire-compiler/{version}/wire-compiler-{version}-jar-with-dependencies.jar".format(version = _WIRE_VERSION),
        ],
        downloaded_file_path = "wire-compiler.jar",
        # SHA256 will be computed on first run - remove this comment after verification
        # sha256 = _WIRE_SHA256,
    )

wire_compiler = module_extension(
    implementation = _wire_compiler_impl,
)
