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
_WIRE_SHA256 = "28173652e42a1ada908119fb5ad37efa195d3e75903eecb594a284c7b1d769ae"

def _wire_compiler_impl(_ctx):
    http_file(
        name = "wire_compiler_jar",
        urls = [
            "https://repo1.maven.org/maven2/com/squareup/wire/wire-compiler/{version}/wire-compiler-{version}-jar-with-dependencies.jar".format(version = _WIRE_VERSION),
        ],
        downloaded_file_path = "wire-compiler.jar",
        sha256 = _WIRE_SHA256,
    )

wire_compiler = module_extension(
    implementation = _wire_compiler_impl,
)
