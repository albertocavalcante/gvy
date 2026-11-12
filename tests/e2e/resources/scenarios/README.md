# E2E Test Scenarios

This directory contains YAML scenario files that define end-to-end LSP test cases.

## Running Scenarios

```bash
# All scenarios
./gradlew :tests:e2eTest

# Specific scenario
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=hover-documentation
```

## Scenario Categories

| Category             | Scenarios                                                                     | Description                   |
| -------------------- | ----------------------------------------------------------------------------- | ----------------------------- |
| **Lifecycle**        | `initialize-basic`                                                            | Server handshake and shutdown |
| **Hover**            | `hover-println`, `hover-documentation`                                        | Hover information             |
| **Completion**       | `completion-basic`, `completion-gdk`                                          | Code completion               |
| **Definition**       | `definition-*`                                                                | Go-to-definition              |
| **Diagnostics**      | `diagnostics-*`                                                               | Error reporting               |
| **Document Symbols** | `document-symbol-basic`                                                       | Document symbols              |
| **Signature**        | `signature-help`                                                              | Method signature help         |
| **Build Tools**      | `gradle-hover-deps`                                                           | Gradle integration            |
| **CodeLens**         | `codelens-spock`                                                              | Test CodeLens commands        |
| **Jenkins**          | `jenkins-pipeline`, `jenkins-completion`, `jenkins-hover`, `jenkins-scripted` | Jenkins pipeline support      |

## Server Mode Migration

> **Note**: Scenarios are being migrated from `mode: stdio` to `mode: inProcess` for faster test execution. See the
> parent [README.md](../../README.md) for details.

Currently migrated:

- ✅ `initialize-basic.yaml`

Pending migration:

- ⏳ All other scenarios

## Cursor Markers and Inline Files

Write `|^|` or `|^name|` where the cursor should be instead of counting lines and columns, and declare the files a
cross-file scenario needs inline under `workspace.files`:

```yaml
name: definition-crossfile-inline
workspace:
  fixture: empty-workspace # optional; inline files are written over it
  files:
    src/main/groovy/com/example/Greeter.groovy: |
      package com.example
      class |^decl|Greeter {}
    src/main/groovy/com/example/App.groovy: |
      package com.example
      new |^use|Greeter()
steps:
  # ...initialize, initialized, wait for groovy/status...
  - openDocument:
      path: "{{cursor.use.path}}" # no text: sends the file as written to disk
  - request:
      method: textDocument/definition
      params:
        textDocument: { uri: "{{cursor.use.uri}}" }
        position: "{{cursor.use}}" # becomes { line: 1, character: 4 }
      saveAs: result
  - assert:
      source: result
      checks:
        - jsonPath: $.items[0].range.start.line
          expect: { type: EQUALS, value: "{{cursor.decl.line}}" }
```

Markers:

- Are recognized in `workspace.files`, `openDocument.text` and full-text `changeDocument.text`. They are stripped before
  the text reaches the server or the disk. A marker in incremental `contentChanges` or `saveDocument.text` fails the
  step, since markers are not processed there.
- Are found after `{{...}}` placeholders are replaced, so positions match the text the server receives. A variable whose
  value contains `|^name|` therefore adds a marker.
- Use names that start with a letter or `_`, followed by letters, digits, `_` or `-`. `line`, `character`, `uri` and
  `path` are reserved. A name, or the anonymous marker, may appear only once per text and once across all inline files.
- Count positions in the text after every marker is removed: zero-based lines (split on `\n`, `\r\n` or `\r`) and UTF-16
  code units, as LSP does by default. An emoji before the marker counts as two characters.
- Belong to the latest text of their document. A later `openDocument` or full-text `changeDocument` of the same document
  replaces all of its cursors, so a name the new text no longer marks stops resolving. Other documents keep theirs.
- Have no escape syntax: any `|^|` or `|^name|` in the text is taken as a marker, even inside a Groovy regex such as
  `~/(foo|^bar|baz)/`. Write such text another way (for example `~/(foo|(^bar)|baz)/`) or put it in a fixture file.

| Placeholder                   | Value                                                                                       |
| ----------------------------- | ------------------------------------------------------------------------------------------- |
| `{{cursor}}`                  | Anonymous marker as an LSP position `{ line, character }`                                   |
| `{{cursor.name}}`             | Named marker as an LSP position                                                             |
| `{{cursor[.name].line}}`      | Zero-based line                                                                             |
| `{{cursor[.name].character}}` | Zero-based UTF-16 column                                                                    |
| `{{cursor[.name].uri}}`       | URI of the document that contained the marker                                               |
| `{{cursor[.name].path}}`      | Decoded, `/`-separated path of that URI relative to the workspace root; an error outside it |

A string that is exactly one placeholder takes the value's JSON type, so `position: "{{cursor.use}}"` is an object and
`value: "{{cursor.decl.line}}"` is a number. A placeholder inside a longer string is replaced by its text. The
high-level `completion` and `rename` steps accept `position:` in place of `line`/`character`. `cursor` is reserved, so
`saveAs: cursor` and `extract` into `cursor` fail.

Inline files:

- Map a workspace-relative path to the file's text. Absolute paths, `..` segments and paths naming a directory are
  rejected. Paths are normalized (`./a//B.groovy` is `a/B.groovy`), and two keys naming the same file are rejected.
- Are written as UTF-8 with LF line endings before `initialize`, replacing fixture files at the same path.

Scenario parsing rejects duplicate YAML keys and unknown fields throughout the scenario schema. Raw protocol JSON
(`params`, `initializationOptions` and expectation values) and map entries such as `env` and inline file paths keep
their own keys. For example, a numeric check is `expect: { type: GTE, value: 1 }`; a check's `message` belongs beside
`jsonPath` and `expect`, rather than inside `expect`. Equality checks require a `value`; `value: null` explicitly
represents JSON null. An empty `expect: {}` checks existence. Snapshot expectations take only a nonblank string path.

## Raw Protocol Parameters

Generic `request` and `notification` steps send the authored `params` JSON value, preserving unknown keys, explicit
nested nulls, and object, array or scalar shapes. Missing object fields remain distinct from fields set to null.
Omitting `params` keeps the SDK's existing `params: null` envelope. Arrays and scalars can exercise invalid-parameter
responses; the server still decides whether a method accepts that shape.

High-level request helpers such as `completion`, `codeAction` and `rename` use generic dispatch and share this raw JSON
transport. Typed initialization and document lifecycle steps, plus the typed `groovy/discoverTests` and `groovy/runTest`
request routes, keep their SDK models and serialization rules. Received raw JSON trees retain explicit nulls for
assertions.

## Numeric JSON Values

YAML JSON fields preserve large integers and supported precise decimal values through protocol transport, response
decoding, JsonPath field/index access and variable extraction. Ordinary integral values use `Long`, larger integers use
`BigInteger`, and supported decimals use `BigDecimal` in the JSON-to-Java bridge. Typed SDK fields keep their declared
types.

`GTE` and `LTE` compare exact numeric values and require two JSON numbers; quoted numeric strings are rejected. `EQUALS`
keeps representation-sensitive structural JSON matching. YAML retains decimal scale, while exponent spelling and the
sign of negative zero can canonicalize. Numeric bounds treat negative zero as zero.

YAML decimal loading and mathematical comparison support BigDecimal's scale range. Larger valid JSON numbers remain
available to raw transport, field/index access and structural equality, while unsupported comparisons fail explicitly.
YAML outside that decimal range and nonfinite forms such as `.nan` and `.inf` fail parsing. Extending the numeric domain
is tracked in [#1700](https://github.com/albertocavalcante/gvy/issues/1700); precise third-party JsonPath numeric filter
comparison is a separate, unverified contract.

## Document Versions

`openDocument` defaults to version 1. When `changeDocument.version` is omitted, YAML and Kotlin steps send the next
version for that URI. Explicit versions are sent unchanged, so scenarios can exercise stale or out-of-order changes; a
later automatic version advances past the highest version prepared in that document lifecycle. Closing and reopening a
document starts a new sequence.

Automatic versions require an earlier high-level `openDocument` or an explicit `changeDocument.version`. Raw
`notification` steps do not update this bookkeeping; use explicit versions when mixing raw document notifications with
high-level change steps. This tracks versions, rather than applying or validating the document's text edits.

## Adding New Scenarios

1. Create `your-test.yaml` in this directory
2. Define the server mode, workspace fixture, and steps
3. Run with `./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=your-test`

See [../../README.md](../../README.md) for full scenario format documentation.
