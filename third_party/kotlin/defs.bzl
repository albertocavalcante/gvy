# Kotlin dependency macros for Buck2
#
# This provides helper macros for defining Kotlin third-party dependencies.

KOTLIN_VERSION = "2.3.0"

KOTLINX_COROUTINES_VERSION = "1.10.1"

def kotlin_jar(name, artifact, sha1, version = None, group = "org.jetbrains.kotlin", **kwargs):
    """Define a Kotlin JAR dependency from Maven Central.

    Args:
        name: Target name
        artifact: Maven artifact ID
        sha1: SHA1 hash of the JAR
        version: Version (defaults to KOTLIN_VERSION)
        group: Maven group ID (defaults to org.jetbrains.kotlin)
        **kwargs: Additional args passed to prebuilt_jar
    """
    if version == None:
        version = KOTLIN_VERSION

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

def maven_jar(name, group, artifact, version, sha1, **kwargs):
    """Define a Maven JAR dependency.

    Args:
        name: Target name
        group: Maven group ID
        artifact: Maven artifact ID
        version: Maven version
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
