"""Module extension to download kotlin-logging jar directly.

GMM (Gradle Module Metadata) causes rules_jvm_external to incorrectly resolve
kotlin-logging-jvm to the sources jar instead of the classes jar. We download
the classes jar directly via http_file to work around this bug.

Tracking: https://github.com/albertocavalcante/gvy/issues/1031
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

_KOTLIN_LOGGING_VERSION = "7.0.14"
_KOTLIN_LOGGING_SHA256 = "ac537321218c670aeeb98c3868d4e21a13ac0b5798125ee60096fb97c197c8f3"

def _kotlin_logging_impl(ctx):
    http_file(
        name = "kotlin_logging_jar",
        urls = [
            "https://repo1.maven.org/maven2/io/github/oshai/kotlin-logging-jvm/{version}/kotlin-logging-jvm-{version}.jar".format(version = _KOTLIN_LOGGING_VERSION),
        ],
        sha256 = _KOTLIN_LOGGING_SHA256,
        downloaded_file_path = "kotlin-logging-jvm.jar",
    )

kotlin_logging = module_extension(
    implementation = _kotlin_logging_impl,
)
