# JVM Dependencies Configuration
#
# This file declares Maven dependencies for the project.
# Run `buck2 bxl //third_party/jvm:pin.bxl:pin` to resolve and generate BUCK files.
#
# TODO(future): Explore full Buck2 integration like rules_jvm_external where
#   dependencies are resolved at analysis time from a lockfile, eliminating
#   the need for a separate pin step. See: https://github.com/bazelbuild/rules_jvm_external

# Maven repositories to search (in order)
REPOSITORIES = [
    "https://repo1.maven.org/maven2/",
    "https://maven.google.com/",
]

# Direct dependencies (Maven coordinates)
# Format: "group:artifact:version"
DEPENDENCIES = [
    # Groovy 4.x (note: moved from org.codehaus.groovy to org.apache.groovy in 4.0)
    "org.apache.groovy:groovy:4.0.30",
    "org.apache.groovy:groovy-json:4.0.30",
    "org.apache.groovy:groovy-xml:4.0.30",
    "org.apache.groovy:groovy-templates:4.0.30",

    # LSP4J - Language Server Protocol
    "org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0",
    "org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0",
]

# Global exclusions (artifacts to never include)
EXCLUSIONS = [
    # Example: "org.slf4j:slf4j-api",
]

# Whether to fetch source JARs (for IDE support)
FETCH_SOURCES = False

# Whether to fetch javadoc JARs
FETCH_JAVADOC = False
