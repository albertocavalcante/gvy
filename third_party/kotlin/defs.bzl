# Maven dependency macros for Buck2
#
# Simple helper for defining Maven JAR dependencies.

# Kotlin version - update this and SHA1 hashes when upgrading
KOTLIN_VERSION = "2.3.0"

KOTLINX_COROUTINES_VERSION = "1.10.1"

def maven_jar(name, group, artifact, version, sha1, **kwargs):
    """Define a JAR dependency from Maven Central.

    Args:
        name: Target name
        group: Maven group ID (e.g., "org.jetbrains.kotlin")
        artifact: Maven artifact ID (e.g., "kotlin-stdlib")
        version: Maven version (e.g., "2.3.0")
        sha1: SHA1 hash of the JAR
        **kwargs: Additional args passed to prebuilt_jar
    """
    url = "mvn:{}:{}:jar:{}".format(group, artifact, version)

    native.remote_file(
        name = name + "_jar",
        out = "{}-{}.jar".format(artifact, version),
        sha1 = sha1,
        url = url,
    )
    native.prebuilt_jar(
        name = name,
        binary_jar = ":{}_jar".format(name),
        **kwargs
    )
