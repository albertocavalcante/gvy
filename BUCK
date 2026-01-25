# Root BUCK file for Groovy LSP
#
# Buck2 build configuration is being introduced gradually alongside
# Gradle (primary) and Bazel build systems for comparison/learning.
#
# See https://buck2.build/docs/ for Buck2 documentation.
#
# Quick start:
#   buck2 build //... --target-platforms=prelude//platforms:default
#   buck2 run //examples/hello:greet --target-platforms=prelude//platforms:default
#
# Example targets are in //examples/hello/...
# Toolchains are in //toolchains/...

# NOTE: Currently requires --target-platforms flag because the prelude's
# genrule rule uses select() which needs platform configuration.
# This is a known limitation with the buck2-prelude for OSS projects.
