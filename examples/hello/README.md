# Hello Example

This directory contains minimal Buck2 examples to verify the build system works.

## Targets

- `//examples/hello:hello` - Generates a simple text file
- `//examples/hello:greet` - Runnable shell script
- `//examples/hello:readme` - Exports this README

## Usage

```bash
# Build all targets in this package
buck2 build //examples/hello/...

# Run the greeting script
buck2 run //examples/hello:greet
buck2 run //examples/hello:greet -- "Buck2 User"

# Show the generated file
buck2 build //examples/hello:hello --show-output
```
