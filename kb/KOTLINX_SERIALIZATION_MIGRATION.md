# Kotlin serialization migration

Status: gvy-owned migrations complete; LSP4J replacement remains deferred in
[#1704](https://github.com/albertocavalcante/gvy/issues/1704). Initialization/configuration work closed
[#704](https://github.com/albertocavalcante/gvy/issues/704). These steps support the existing
[Quality Bar](../docs/quality-bar.md); they do not introduce another product roadmap.

## Goal and boundary

Use kotlinx.serialization for gvy-owned JSON models and resource/configuration handling. Confine Gson to explicit
LSP4J/BSP4J integration and protocol-test adapters while those dependencies remain.

The current language-server launcher and BSP connector both use LSP4J JSON-RPC, which requires Gson. Migrating gvy's own
codecs does not remove Gson from the shipped dependency graph. Do not exclude its runtime artifact to make a dependency
report appear clean.

Jenkins metadata, SARIF, AST models and BSP connection descriptors already use kotlinx.serialization. The remaining
production Gson use at the start of this work is the Gradle/JDK resource reader, initialization/settings conversion, and
status/error serialization.

## Completed PR sequence

Each PR receives independent review of its complete final diff, focused native validation, applicable CI and verified
squash landing. Detailed review heads, findings, fixes, validation and remaining limits are recorded in the linked PRs.

1. [x] **Bundled Gradle/JDK resources ([#1722](https://github.com/albertocavalcante/gvy/pull/1722)).** Replace the
       resource reader's Gson calls with typed kotlinx DTOs. Tolerate resource metadata and preserve nullable entries,
       missing/malformed-resource fallback and unknown class-major behavior. Remove the now-unused direct Gson build
       dependency. Keep compatibility-data consolidation separate: existing services have different fallback contracts.
2. [x] **Initialization and settings ([#1725](https://github.com/albertocavalcante/gvy/pull/1725)).** Share one LSP4J
       boundary conversion for incoming JSON and direct map callers. Use kotlinx JSON past that boundary and preserve
       the current configuration model's dotted keys, numeric coercion, bounds, nested workers and accepted string/list
       forms. Empty settings objects still select defaults; non-object change notifications still leave active
       configuration unchanged. Close #704 only after both paths are validated.
3. [x] **Status and errors ([#1724](https://github.com/albertocavalcante/gvy/pull/1724)).** Move models and wire
       serialization to kotlinx and register a narrow adapter with the production LSP4J launcher. Verify actual outgoing
       frames contain the client's string health values. Preserve typed error fields, dynamic generic discriminators,
       unknown-type fallback and isolation of malformed optional details. Preserve default/null emission and
       computed-property behavior unless a separately tested contract change is needed.
4. [x] **Final inventory.** Check production imports, all supported build declarations and test bridges. Record each
       retained Gson path and its retirement condition. Keep actual protocol tests alongside model-level codec tests;
       serializing with a standalone JSON library does not prove what the launcher sends.

The standalone Maven BSP connection-file tests also use kotlinx JSON, preserving their existing assertions and null
rejection. Their direct Bazel Gson dependency was removed; kotlinx core/JSON dependencies are declared explicitly.

## Retained Gson boundaries

The production import inventory is now confined to two files in the server's `transport.lsp4j` package:

| Boundary                                                  | Why Gson remains                                                                                                                                                                           | Retirement condition                                                              |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------- |
| `ConfigurationPayload.kt`                                 | Accepts Gson objects produced by LSP4J, then parses with kotlinx JSON and converts values for the existing configuration model. Native kotlinx objects and direct maps are also supported. | Replace the incoming protocol boundary with LSP4K after #1704 readiness checks.   |
| `StatusNotificationAdapter.kt`                            | Registers the pure kotlinx status/error codec with LSP4J's Gson launcher.                                                                                                                  | Replace LSP4J's launcher and remove this temporary registration.                  |
| BSP4J/LSP4J JSON-RPC runtime                              | Both protocol integrations still require Gson transitively.                                                                                                                                | Validate a replacement BSP integration as well as the language-server migration.  |
| Protocol-test bridges and malformed LSP4J-object fixtures | Typed Java LSP objects and raw dynamic JSON still cross the current SDK boundary. Numeric fidelity and raw-parameter handling remain deliberate protocol behavior.                         | Migrate the corresponding SDK/test-client path and preserve its wire regressions. |

The `gls` Bazel Gson dependency remains necessary for its retained adapters. The shared Buck2 third-party Gson
declaration remains alongside the LSP4J inventory. Buck2 currently has no gvy application target; this batch does not
claim new Buck2 application validation. Gson names inside Groovy import/navigation fixtures are test input, not
additional application JSON codecs.

The configuration model deliberately remains map-based to preserve its dotted keys and compatibility behavior; a
typed-model redesign is separate from removing unchecked Gson conversion. Overlapping Gradle/JDK compatibility services
also remain separate because their fallback contracts differ. Build-tool maintainers own any consolidation: first
inventory the differences and choose a canonical data source, then prove mapped and fallback behavior before retiring
duplicate resources. These limits do not justify changing current behavior during a serializer migration.

## Validation

- Resource PR: existing classpath-resource tests plus explicit missing/malformed-resource fallback tests, a genuinely
  unmapped class-major case and inspection of the built JAR's resources. Run relevant build-tool checks and validate
  supported build declarations; source-classpath tests alone do not prove packaged-resource behavior.
- Configuration PR: initialization and configuration tests through real JSON-RPC, plus direct-map, nested-value,
  malformed-input, numeric-bound and default behavior.
- Status PR: all named/generic error variants, unknown and malformed details, default/null behavior, actual outgoing
  status frames, typed receiving and a subsequent usable request. Check the VS Code status contract.
- BSP connection-file tests: preserve all existing assertions while replacing the inspection parser, including rejection
  of JSON null in the version and argv checks.
- Every code PR: canonical formatting/lint and relevant broader checks, final publication-head CI and diagnostic review.
  Follow [CI accountability](../docs/ci-diagnostics.md); do not suppress new findings or substitute an unrelated head's
  result. Match documentation-only validation to [CONTRIBUTING](../CONTRIBUTING.md#validate-your-changes).

## TODO: replace LSP4J with LSP4K when ready

**TODO(#1704): migrate gvy's language-server protocol types and runtime from LSP4J to Alberto's LSP4K once its required
protocol and transport behavior is ready and validated.** This migration is explicitly deferred. LSP4K's README or
existence alone does not establish readiness for gvy.

The gvy integration owner must first verify coverage of all advertised capabilities and custom methods, union and
dynamic payloads, cancellation/progress, initialization/shutdown/exit, errors and concurrent request handling. Both
stdio and socket transports need UTF-8 framing and lifecycle tests. The replacement needs reproducible dependency
resolution across Gradle, Bazel and Buck2 and compatibility with gvy's supported Java runtime. Run gvy protocol and
editor integration tests against it before changing the production launcher.

After that language-server migration, audit BSP4J separately: its LSP4J JSON-RPC integration can still retain Gson.
Retire the temporary adapters and unused declarations only when their callers have migrated. Verify the resolved runtime
graph and packaged server before claiming complete Gson removal.

Remaining debt owner: gvy maintainers for integration and dependency retirement; LSP4K/jsonrpc4k maintainers for library
capability gaps. Next action and acceptance criteria are tracked in #1704. Changes to those libraries are outside this
gvy migration batch.
