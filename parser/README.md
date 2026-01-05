# Parser Modules

This directory contains the Groovy parsing libraries used by the Groovy Language Server.

## Modules

### `native/`

**Gradle project**: `:parser:native`

Parser implementation tightly coupled to Groovy's **native AST types** (`ClassNode`, `MethodNode`, `FieldNode`, etc.).
Used internally by the LSP for:

- Error-recovery parsing
- AST traversal with parent tracking
- Position-based node lookup
- Groovy macro support

**When to use**: Internal LSP features that need direct access to Groovy compiler internals.

---

### `core/`

**Gradle project**: `:parser:core`

Standalone parsing library with a **JavaParser-inspired API**. Provides:

- Clean, well-documented AST hierarchy
- `VoidVisitor` and `GroovyVisitor<R>` patterns
- Comment preservation and attribution
- Source position tracking
- Symbol resolution (type solvers, contexts)
- Jenkins CPS analysis support
- Multi-version Groovy support (2.4 through 5.x)

**When to use**: External consumers, IDE tooling, or features needing a stable public API.

See [core/README.md](core/README.md) for full documentation.

---

## Architecture

```
parser/
├── native/          # Groovy native AST coupling
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/...
│       └── test/kotlin/...
└── core/            # JavaParser-inspired API
    ├── build.gradle.kts
    ├── README.md    # Full API documentation
    └── src/
        ├── main/kotlin/...
        └── test/kotlin/...
```

## Future Considerations

The `core/` module includes symbol resolution in the `resolution/` package. If this grows significantly (>15K lines) or
external consumers need a lightweight parse-only artifact, consider splitting into:

- `parser/core` — Pure parsing, AST, visitors
- `parser/symbol-solver` — Type resolution, classpath scanning

Current state: ~6K lines resolution code, tightly integrated with AST. No split needed yet.
