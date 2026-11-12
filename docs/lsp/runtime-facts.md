# Runtime facts request

After initialization, a client can send the no-parameter JSON-RPC request `groovy/runtimeFacts`. Older servers may
return `MethodNotFound`; treat that as unavailable rather than inferring the runtime or engine from requested settings.
The request reads the running JVM and the current hover-engine manager state. It does not start an engine, parse a
document, resolve dependencies or read the workspace.

The result is an object with `schemaVersion: 1` and three objects:

- `runtime`: `processState` and optional `processId`, `javaState` and optional `javaFeature`/`javaVersion`, and
  `heapState` and optional `maxHeapBytes`. Values come from the running process, not launch arguments.
- `build`: `versionState` and optional `version`, `commitState` and optional `commit`, and `dirtyState` and optional
  `dirty`. These are bounded embedded build metadata, not proof of the JAR's origin or integrity.
- `hoverEngine`: `configured`, `state`, and optional `instantiated`. `configured` is the engine manager's effective
  configuration at observation time; `instantiated` is the ID of its current instance. `state` is `known`, `unknown`, or
  `not_instantiated`. The OpenRewrite configuration ID is `openrewrite`; its instance ID is `rewrite`.

For runtime and build values, states are `known`, `unknown`, or `malformed`. A known state requires its value; unknown
and malformed states omit it. Consumers should reject explicit null, a missing known value, or an unexpected state
rather than treating those as verified facts. Process ID and maximum heap are positive integers no larger than 2^53-1;
version strings and commits are constrained before emission. The result excludes command-line arguments, environment
variables, paths, workspace contents, branch names and build URLs.

The engine snapshot is not an attribution to a particular hover: reconfiguration can occur before or after a request.
Only the engine/hover path is described; other language features can use different implementations. Startup applies
initial engine settings during the `initialized` notification, so a query immediately after `initialize` may observe the
manager's earlier configuration. Request correlation and a coherent analysis session are separate work.

Before `initialize` succeeds, the request fails with `ServerNotInitialized`; after shutdown is requested it fails with
`InvalidRequest`. It adds no fields to standard LSP initialization responses.
