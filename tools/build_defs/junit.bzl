"""Module extension to download JUnit jars directly.

GMM (Gradle Module Metadata) creates circular dependencies between JUnit modules
when using variant selection. We download the jars directly via http_file to
work around this bug.

GMM creates cycles like:
- launcher -> engine -> launcher (Platform modules)
- jupiter-engine -> jupiter-params -> jupiter-engine (Jupiter modules)

These cycles are caused by GMM generating incorrect bidirectional dependencies
that don't exist in the actual Maven POMs.

Tracking: https://github.com/albertocavalcante/gvy/issues/1032
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

# JUnit 6.0.2 versions
_JUNIT_VERSION = "6.0.2"

# SHA256 checksums for the jars
_JARS = {
    # JUnit Jupiter modules
    "junit-jupiter": "ca5466a435a0b0f100382c5a77e91eae6d4ca330f60b4d24013a9a3f0cd742e8",
    "junit-jupiter-api": "407b20aded40781ad5440309639ef0c428e735b2b6f8831c5d2ff9041613195d",
    "junit-jupiter-engine": "2a7201cf4890483c1bb5045e91a59b7052a8189b2930a9be9514c63e1e58b3b6",
    "junit-jupiter-params": "4c6f5877e852a7b891207c6e8d6a5eb19680d25b0c401d09a649d1ea17117b59",
    # JUnit Platform modules
    "junit-platform-launcher": "bd9910f7bca4a9d4050ba85e1f076e3b8573239d45df79673862aab298595792",
    "junit-platform-engine": "31495b754ba882ba693c19fa9d887e61af6f3850f58741bd58c3f129deca5517",
    "junit-platform-commons": "1c49eab8ed3d81280459e3f3e1f119b928f9cfc530c7500c2a15a9ae8e7ad30d",
    "junit-platform-console": "adfe7a292d6d8a48b16565cf807aac3fff1a56bc4be88b26707f3b20c902b2fd",
}

def _junit_impl(ctx):
    for name, sha256 in _JARS.items():
        # Determine the Maven group (jupiter vs platform)
        if name.startswith("junit-jupiter"):
            group = "org/junit/jupiter"
        else:
            group = "org/junit/platform"

        http_file(
            name = name.replace("-", "_") + "_jar",
            urls = [
                "https://repo1.maven.org/maven2/{group}/{name}/{version}/{name}-{version}.jar".format(
                    group = group,
                    name = name,
                    version = _JUNIT_VERSION,
                ),
            ],
            sha256 = sha256,
            downloaded_file_path = "{}.jar".format(name),
        )

junit = module_extension(
    implementation = _junit_impl,
)
