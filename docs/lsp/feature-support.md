# LSP Feature Support

Groovy LSP targets Language Server Protocol (LSP) 3.17. This matrix is a provisional description of development-source
capabilities, not a release scorecard or a guarantee of correctness. The source inspected is commit `f101e5bf5`;
validation against a feature scorecard is tracked in [#1550](https://github.com/albertocavalcante/gvy/issues/1550). The
stable v0.5.4 VSIX bundles a 0.4.8 server; see the [release limitation](../installation.md#stable-release-limitation).
Client support and project context also affect results.

Legend:

- yes: supported and advertised to clients
- partial: implemented but limited or not advertised yet
- planned: on the roadmap, not implemented
- no: not implemented

## Lifecycle

| Method        | Support | Notes                           |
| ------------- | ------- | ------------------------------- |
| `initialize`  | yes     | Initializes server capabilities |
| `initialized` | yes     | Kicks off background setup      |
| `shutdown`    | yes     | Graceful shutdown               |
| `exit`        | yes     | Process exit                    |

## Text document sync

| Method                           | Support | Notes                                |
| -------------------------------- | ------- | ------------------------------------ |
| `textDocument/didOpen`           | yes     | Triggers compilation and diagnostics |
| `textDocument/didChange`         | yes     | Full sync only                       |
| `textDocument/didSave`           | yes     |                                      |
| `textDocument/didClose`          | yes     | Clears diagnostics                   |
| `textDocument/willSave`          | no      |                                      |
| `textDocument/willSaveWaitUntil` | no      |                                      |

## Language features

| Method                                   | Support | Notes                                                |
| ---------------------------------------- | ------- | ---------------------------------------------------- |
| `textDocument/completion`                | yes     | Keywords + AST-based symbols                         |
| `completion/resolve`                     | no      |                                                      |
| `textDocument/hover`                     | yes     | Type info + documentation                            |
| `textDocument/signatureHelp`             | yes     | Method parameter hints                               |
| `textDocument/definition`                | yes     |                                                      |
| `textDocument/typeDefinition`            | yes     |                                                      |
| `textDocument/implementation`            | yes     | Advertised; captured workspace queries               |
| `textDocument/references`                | yes     |                                                      |
| `textDocument/documentSymbol`            | yes     |                                                      |
| `textDocument/documentHighlight`         | yes     |                                                      |
| `textDocument/rename`                    | yes     | Versioned edits; unproven targets rejected           |
| `textDocument/prepareRename`             | yes     | Negotiated through client prepareSupport             |
| `textDocument/codeAction`                | yes     | Quick fixes + formatting                             |
| `codeAction/resolve`                     | no      |                                                      |
| `textDocument/codeLens`                  | yes     | Test run/debug lenses                                |
| `codeLens/resolve`                       | no      |                                                      |
| `textDocument/foldingRange`              | yes     | Owned structures, imports and block comments         |
| `textDocument/formatting`                | yes     | OpenRewrite                                          |
| `textDocument/rangeFormatting`           | no      |                                                      |
| `textDocument/onTypeFormatting`          | no      |                                                      |
| `textDocument/semanticTokens/full`       | partial | Groovy and Jenkins; current source and UTF-16 ranges |
| `textDocument/semanticTokens/range`      | no      |                                                      |
| `textDocument/semanticTokens/full/delta` | no      |                                                      |
| `textDocument/inlayHint`                 | partial | Advertised; dynamic settings pending (#566)          |
| `textDocument/inlineValue`               | no      |                                                      |
| `textDocument/selectionRange`            | yes     | Captured source and nested structural selections     |
| `textDocument/documentLink`              | no      |                                                      |
| `textDocument/documentColor`             | no      |                                                      |
| `textDocument/colorPresentation`         | no      |                                                      |
| `textDocument/declaration`               | no      |                                                      |
| `textDocument/linkedEditingRange`        | no      |                                                      |
| `textDocument/moniker`                   | no      |                                                      |
| `textDocument/prepareCallHierarchy`      | yes     | Advertised; captured source ranges                   |
| `callHierarchy/incomingCalls`            | partial | Advertised; cross-file resolution limited            |
| `callHierarchy/outgoingCalls`            | partial | Advertised; cross-file resolution limited            |
| `textDocument/prepareTypeHierarchy`      | no      |                                                      |
| `typeHierarchy/supertypes`               | no      |                                                      |
| `typeHierarchy/subtypes`                 | no      |                                                      |

## Diagnostics

| Method                            | Support | Notes                            |
| --------------------------------- | ------- | -------------------------------- |
| `textDocument/publishDiagnostics` | yes     | Push-based diagnostics           |
| `textDocument/diagnostic`         | no      | Pull diagnostics not implemented |
| `workspace/diagnostic`            | no      | Pull diagnostics not implemented |

## Workspace

| Method                                | Support | Notes                                                       |
| ------------------------------------- | ------- | ----------------------------------------------------------- |
| `workspace/symbol`                    | yes     |                                                             |
| `workspace/executeCommand`            | partial | `groovy.version`; advertised; unknown fails (InvalidParams) |
| `workspace/didChangeConfiguration`    | yes     |                                                             |
| `workspace/didChangeWatchedFiles`     | yes     | Registered dynamically when supported                       |
| `workspace/didChangeWorkspaceFolders` | no      |                                                             |
| `workspace/configuration`             | no      |                                                             |
| `workspace/applyEdit`                 | no      |                                                             |
| `workspace/willCreateFiles`           | no      |                                                             |
| `workspace/didCreateFiles`            | no      |                                                             |
| `workspace/willRenameFiles`           | no      |                                                             |
| `workspace/didRenameFiles`            | no      |                                                             |
| `workspace/willDeleteFiles`           | no      |                                                             |
| `workspace/didDeleteFiles`            | no      |                                                             |
| `workspace/codeLens/refresh`          | no      |                                                             |
| `workspace/semanticTokens/refresh`    | no      |                                                             |
| `workspace/inlayHint/refresh`         | no      |                                                             |

<a id="window"></a>

## Window and telemetry

| Method                           | Support | Notes                             |
| -------------------------------- | ------- | --------------------------------- |
| `window/showMessage`             | yes     | Startup and error notifications   |
| `window/showMessageRequest`      | no      |                                   |
| `window/logMessage`              | no      |                                   |
| `window/workDoneProgress/create` | yes     | Dependency resolution progress    |
| `$/progress`                     | yes     | WorkDoneProgress updates          |
| `telemetry/event`                | yes     | Formatting + definition telemetry |

## Notebook documents

| Method                       | Support | Notes |
| ---------------------------- | ------- | ----- |
| `notebookDocument/didOpen`   | no      |       |
| `notebookDocument/didChange` | no      |       |
| `notebookDocument/didSave`   | no      |       |
| `notebookDocument/didClose`  | no      |       |

## Capability alignment notes

- `textDocument/prepareRename` is advertised only when the client declares rename preparation support.
- `textDocument/selectionRange` preserves request order, with identifier, structural, line and document selections.
  Incomplete or stale AST data falls back to current text. A document change during a request returns `ContentModified`.
  Character offsets beyond a line clamp to its end; negative positions and invalid lines return `InvalidParams`.
  Requests support at most 1,024 positions and 64 nested ranges per position; larger batches return `InvalidParams`. The
  outermost document range is retained when limiting depth. Unknown documents return null.
- `textDocument/foldingRange` uses one captured source/parse pair. It includes owned class, method and block regions,
  contiguous import groups and lexer-proven block comments. Structural regions retain the closing-brace line; imports
  and comments include their last line. Source changes reject the request, cancellation propagates, and cold queries do
  not publish compilation state. Unknown documents return an empty list. Responses are sorted, deduplicated and capped
  at 10,000 ranges; this bounds response count, not document-processing cost.
- `workspace/executeCommand` is implemented for `groovy.version` only and advertised via `executeCommandProvider`.
  Unknown commands fail with an `InvalidParams` error naming the command.

## Coverage response migration

The `groovy/getCoverage` response reports whether each line was covered. Its former `hitCount` field has been removed:
JaCoCo supplies covered instruction counts, not execution counts. Clients should use the Boolean `covered` field. In the
protobuf schema, field number 3 and the name `hit_count` are reserved to prevent reuse.
