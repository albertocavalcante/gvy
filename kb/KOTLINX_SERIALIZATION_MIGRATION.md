# Kotlin serialization migration

Status: implementation planned. Track initialization/configuration in
[#704](https://github.com/albertocavalcante/gvy/issues/704) and the eventual LSP4J replacement in
[#1704](https://github.com/albertocavalcante/gvy/issues/1704). These steps support the existing
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

## Small PR sequence

Each PR receives independent review of its complete final diff, focused native validation, applicable CI and verified
squash landing. Implementation status and evidence belong in the linked PRs; unchecked steps below remain planned.

1. [ ] **Bundled Gradle/JDK resources.** Replace the resource reader's Gson calls with typed kotlinx DTOs. Tolerate
       resource metadata and preserve nullable entries, missing/malformed-resource fallback and unknown class-major
       behavior. Remove the now-unused direct Gson build dependency. Keep compatibility-data consolidation separate:
       existing services have different fallback contracts.
2. [ ] **Initialization and settings.** Share one LSP4J boundary conversion for incoming JSON and direct map callers.
       Use kotlinx JSON past that boundary and preserve the current configuration model's dotted keys, numeric coercion,
       bounds, nested workers and accepted string/list forms. Empty settings objects still select defaults; non-object
       change notifications still leave active configuration unchanged. Close #704 only after both paths are validated.
3. [ ] **Status and errors.** Move models and wire serialization to kotlinx and register a narrow adapter with the
       production LSP4J launcher. Verify actual outgoing frames contain the client's string health values. Preserve
       typed error fields, dynamic generic discriminators, unknown-type fallback and isolation of malformed optional
       details. Preserve default/null emission and computed-property behavior unless a separately tested contract change
       is needed.
4. [ ] **Final inventory.** Check production imports, all supported build declarations and test bridges. Record each
       retained Gson path and its retirement condition. Keep actual protocol tests alongside model-level codec tests;
       serializing with a standalone JSON library does not prove what the launcher sends.

## Validation

- Resource PR: existing classpath-resource tests plus explicit missing/malformed-resource fallback tests, a genuinely
  unmapped class-major case and inspection of the built JAR's resources. Run relevant build-tool checks and validate
  supported build declarations; source-classpath tests alone do not prove packaged-resource behavior.
- Configuration PR: initialization and configuration tests through real JSON-RPC, plus direct-map, nested-value,
  malformed-input, numeric-bound and default behavior.
- Status PR: all named/generic error variants, unknown and malformed details, default/null behavior, actual outgoing
  status frames, typed receiving and a subsequent usable request. Check the VS Code status contract.
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
