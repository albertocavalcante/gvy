# Hello Example

This directory contains minimal Buck2 examples to verify the build system works.

## Targets

- `//examples/hello:hello` - Generates a simple text file
- `//examples/hello:greet` - Runnable shell script
- `//examples/hello:readme` - Exports this README

## Usage

Note: All commands require `--target-platforms=prelude//platforms:default` due to the prelude's use of `select()` in
rule definitions.

```bash
# Build all targets in this package
buck2 build //examples/hello/... --target-platforms=prelude//platforms:default

# Run the greeting script
buck2 run //examples/hello:greet --target-platforms=prelude//platforms:default
buck2 run //examples/hello:greet --target-platforms=prelude//platforms:default -- "Buck2 User"

# Show the generated file
buck2 build //examples/hello:hello --show-output --target-platforms=prelude//platforms:default
```
