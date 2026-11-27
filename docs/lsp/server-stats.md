# Server stats request

After initialization, a client can send the no-parameter JSON-RPC request `groovy/serverStats`. Older servers may return
`MethodNotFound`; treat that as unavailable. The request reads numbers the server already recorded. It does not start
work, resolve dependencies, parse a document or read the workspace.

The result is an object with `schemaVersion: 1` and two objects:

- `build`: the same bounded build stamp as in [runtime facts](runtime-facts.md): `versionState` and optional `version`,
  `commitState` and optional `commit`, and `dirtyState` and optional `dirty`.
- `startup`: how long each startup phase took.

```json
{
  "schemaVersion": 1,
  "build": {
    "versionState": "known",
    "version": "0.5.4-dev+g1234567",
    "commitState": "known",
    "commit": "0123456789abcdef0123456789abcdef01234567",
    "dirtyState": "known",
    "dirty": false
  },
  "startup": {
    "serverStartJvmUptimeMs": 310,
    "observedOffsetMs": 5200,
    "phases": [
      { "name": "lsp.initialize", "status": "completed", "startOffsetMs": 4, "durationMs": 38 },
      { "name": "workspace.setup", "status": "completed", "startOffsetMs": 90, "durationMs": 120 },
      { "name": "dependencies.resolve", "status": "completed", "startOffsetMs": 215, "durationMs": 3900 },
      { "name": "workspace.model.publish", "status": "completed", "startOffsetMs": 4120, "durationMs": 210 },
      { "name": "workspace.index", "status": "running", "startOffsetMs": 4340 },
      { "name": "workspace.compile", "status": "pending" }
    ]
  }
}
```

## Startup timings

All times are whole milliseconds, rounded down, and come from a monotonic clock. They are measured from the server
start, which is when the language server object is created. `serverStartJvmUptimeMs` is how long the JVM had already run
at that moment; it is left out when the JVM does not say. `observedOffsetMs` is the time of this request on the same
scale.

`phases` always lists all six phases in this order:

| Phase                     | What it covers                                                                                        |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| `lsp.initialize`          | Handling the `initialize` request.                                                                    |
| `workspace.setup`         | Opening the workspace and starting project strategies, before dependency resolution.                  |
| `dependencies.resolve`    | Resolving the build's dependencies, from the start of the request to its first outcome.               |
| `workspace.model.publish` | Publishing the resolved classpath and source roots to the compiler, and what it rebuilds in response. |
| `workspace.index`         | Indexing the workspace's source files.                                                                |
| `workspace.compile`       | The one-time compile of the whole workspace, done only within the file-count limit.                   |

Each phase has a `status`:

| Status      | Meaning                                                                     | Numbers present               |
| ----------- | --------------------------------------------------------------------------- | ----------------------------- |
| `pending`   | The phase has not begun.                                                    | none                          |
| `running`   | The phase began and has not ended.                                          | `startOffsetMs`               |
| `completed` | The step returned. It does not say the step found no errors.                | `startOffsetMs`, `durationMs` |
| `failed`    | The step threw, or the server acted on a failed outcome (see below).        | `startOffsetMs`, `durationMs` |
| `cancelled` | The step was stopped, for example by shutdown.                              | `startOffsetMs`, `durationMs` |
| `skipped`   | The phase will not run: there was nothing to do or an earlier phase failed. | none                          |

A workspace with no root skips all five workspace phases. A failed dependency resolution skips the three phases after
it, because the server then neither publishes a model nor indexes the workspace. A workspace with no source files skips
`workspace.index` and `workspace.compile`; one over the file-count limit skips only `workspace.compile`. A failed index
skips `workspace.compile`.

### How to read the numbers

- **First run only.** Each phase records its first run after the server starts. A later build-file change repeats
  resolution, publishing, indexing and compiling, but those runs are not recorded here.
- **Phases overlap and leave gaps.** Resolution ends on a background thread, and indexing runs after publishing. The
  time between the end of one phase and the start of the next is work the stats do not name, such as the Java version
  check that runs before resolution.
- **`failed` means the first outcome the server acted on** for `dependencies.resolve`. The server publishes its failure
  status at that same moment. A build-file watcher that fails to start is reported through the same failure path while
  resolution carries on, so `failed` can appear before resolution has finished. This is tracked in
  [#2514](https://github.com/albertocavalcante/gvy/issues/2514).

## Compatibility

Adding an optional field or a new phase keeps `schemaVersion: 1`, so consumers should ignore names and fields they do
not know. Renaming or removing a field or phase, or changing what a value means, raises `schemaVersion`.

The result holds only numbers, `true` or `false`, and fixed words: phase names, statuses, and the build stamp states
`known`, `unknown` and `malformed`. It never contains file paths, URIs, project names, exception text or environment
values.

Before `initialize` succeeds, the request fails with `ServerNotInitialized`; after shutdown is requested it fails with
`InvalidRequest`. It adds no fields to standard LSP initialization responses.
