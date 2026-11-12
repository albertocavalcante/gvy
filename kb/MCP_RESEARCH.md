# MCP patterns from gopls and other language tools for gvy

Date: 2026-11-11. Status: research proposal; no MCP support is implemented by this record.

Evaluation and the go/no-go decision are tracked in [#1727](https://github.com/albertocavalcante/gvy/issues/1727).

## Answer and scope

gvy has a credible use for MCP: expose a small set of semantic queries with explicit analysis readiness, source
versions, and completeness. Start by testing representative agent workflows through an existing Groovy-capable LSP
bridge. If the experiment demonstrates value, implement a native MCP adapter over shared workspace services, beginning
with headless stdio and later adding editor-attached access.

gopls and Metals are verified examples of native MCP support. Dart and Flutter offer a broader SDK/tooling MCP surface.
Serena and cclsp demonstrate external LSP adaptation. These are different architectures; support through a bridge does
not mean an upstream language server implements MCP itself.

This record supports the [Quality Bar](../docs/quality-bar.md) and the ecosystem-learning track
[#1548](https://github.com/albertocavalcante/gvy/issues/1548). It evaluates implemented MCP patterns from language
tooling against gvy's architecture. Scope: representative public primary sources and pinned gvy source, rather than an
exhaustive catalogue. Recommendations would change if a bridge cannot synchronize gvy reliably, if existing analysis
services cannot provide coherent results, or if semantic tools add no benefit on actual tasks.

All web evidence was accessed on 2026-11-11. Code links are pinned where practical. Current implementation and reported
rationale are distinguished from proposals. No token or latency savings were measured.

## What upstream has implemented

### gopls

Native MCP arrived experimentally in gopls v0.20.0, released in July 2025 [S01]. The documented modes are headless
`gopls mcp` over stdio, reading saved files, and attached MCP alongside an LSP session, sharing memory and unsaved
editor buffers [S02]. The v0.24.0 tag resolves to commit `1f9922d1dcc5836d5c5bcb552c8fed230f885372`.

The v0.24.0 source enables eight default tools: workspace, search, file context, package API, symbol references,
diagnostics, rename-symbol, and vulnerability checks. The same default list was observed at upstream tip
`d4453b555d58ab596341eb6965a58aa1f3f7a0a8` [S03]. The relevant design choices are unusually explicit:

- Position-based references are disabled by default because the authors found models unreliable at specifying locations.
  File plus symbol is the default alternative.
- A context tool that summarizes all imports is disabled because it can consume substantial context; selective API
  summaries are preferred.
- Workspace diagnostics are preferred over a file-only view because a change can break another file.

These comments are upstream design rationale, not controlled performance evidence. Rename computes changes and returns a
unified diff; the handler does not apply them [S04]. Workflow instructions are available separately and are not
automatically injected during initialization [S02, S05]. gvy should use short task-specific guidance rather than copy
the upstream instructions' blanket workflow obligations.

The attached HTTP implementation still uses legacy SSE. Its source also acknowledges gaps in headless change detection:
mtime checks can miss rapid edits, added files, or go.mod changes [S03]. Shared semantic state is worth learning from;
those transport and freshness limitations are not a template to reproduce.

### Metals

Metals introduced MCP in v1.5.3 and now documents both standalone and editor-connected use, stdio and Streamable HTTP,
semantic tools, and build/test workflows [S06]. Its surface includes symbol search, member and overload inspection, docs
and source retrieval from dependencies, usage search, build-target listing, compilation, testing, build import, and
refactoring support.

The most relevant lesson for Groovy is classpath-aware JVM API inspection: agents need the methods available in the
project's actual dependency versions. Its source waits for indexing before semantic queries, supports explicit
build-target selection for inspection, and exposes capped search results [S07]. Standalone mode composes the existing
project language service rather than introducing another analyzer, and explicitly shuts down when stdin closes [S08].

### Dart and Flutter

The official MCP suite combines analysis and symbol resolution with package management, testing, and live runtime
inspection [S09]. Its source exposes an LSP tool alongside task tools, and supports enabling/disabling tool categories;
several execution and modification tools are off by default [S10]. This is a broader SDK/tooling product, not evidence
that every language server needs that entire surface.

For gvy, the lesson is to expose coherent tasks selectively and attach session provenance to live answers. Runtime
execution is outside the proposed first scope.

### Serena and cclsp

Serena's tools use symbol paths, optional source bodies, bounded output, and snippets at reference locations [S11]. Its
Codex context deliberately excludes generic file/shell operations already provided by the host [S12]. Its existing
Groovy adapter accepts a user-supplied JVM language-server JAR; its own guide describes Groovy support as incomplete
[S13]. This is a promising experiment route, not verified gvy compatibility.

cclsp's history records a move from line/column inputs to symbol-name/kind inputs. It later added actual rename
application with a preview mode [S14]. That contrast matters: an MCP method named rename can return a proposal or modify
the tree. gvy should make this explicit and initially return previews only.

Representative rust-analyzer, clangd, and TypeScript support was found in external adapters. No claim is made here about
the absence of other native implementations. A search of clojure-lsp's current tree for MCP paths produced no matches;
that narrow search does not establish that no integration exists.

## gvy's observed architecture

Reviewed gvy source: `95cdd85dfe0c541eedfd6d5cb16b22207a37976c`. Source observations below describe this revision. A
tracked-source grep at that revision found no existing MCP implementation or explicit MCP design [S15].

The LSP server currently composes its document store, compilation services, workspace/text services, and startup manager
in private fields. That is the natural basis for a shared runtime, but extracting it is real work. `LanguageSession` is
per parsed document and only covers a subset of features; it is not a ready-made backend for every proposed tool. Socket
mode creates a new server per connection, so merely connecting to the socket does not share an editor's state [S15].

Existing semantic identifiers include class names and method signatures, but erased parameter types use simple names and
local IDs include declaration positions. A new selector should return snapshot-bound opaque handles with candidates,
rather than treat every name or existing identifier as globally unique. Current workspace search is name substring
matching, not a full agent selector [S15].

The unusually valuable existing contract is `DiagnosticsRunResult`: URI, client version, run ID, configuration
generation, provider IDs, provider failures, and completeness. Internal `awaitCurrentDiagnostics(uri, version)` can
await and validate the exact run, while public `diagnose()` returns only a list. A native facade could preserve
information that a generic bridge cannot obtain through the current public endpoint [S15].

There are constraints a new interface must retain:

- Rename refuses targets used from other files because it edits only the current file. Returned `WorkspaceEdit` objects
  can include versioned text edits and a file rename. A preview tool must preserve refusal and show resource operations.
- Reference providers still contain error-to-empty fallbacks. They need structured failure propagation before an agent
  treats an empty list as proof of no uses.
- Dynamic dispatch and unresolved names are represented in semantic documents. Static references cannot prove the
  absence of reflective, metaprogrammed, or DSL callers.
- Delegation resolution has gaps for nested closures, runtime delegate assignment, and annotation extraction. Hover and
  other navigation can also use different parser engines.
- Some hierarchy getters remain stubs. Do not put hierarchy tools in the first contract by wrapping those getters.

The governing quality bar calls for explicit uncertainty, refusal over guessed edits, and measured quality; its
scorecard targets are not evidence of current achieved quality [S16]. This work should fit that existing quality track
rather than introduce a competing roadmap.

## Proposed design

| Decision                       | Pattern                                                                             | Reason                                                                            |
| ------------------------------ | ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| Adopt                          | Symbol selection, bounded output and explicit analysis completion                   | These support correct, honest answers without requiring guessed cursor positions. |
| Adapt                          | Native MCP over shared workspace services                                           | Validate task value first, then expose gvy's existing analysis authority.         |
| Defer                          | Editor attachment, execution tools and DSL explanations                             | They need explicit session, lifecycle and semantic contracts.                     |
| Reject for the initial surface | Arbitrary command execution, guessed rename targets and automatic patch application | These exceed the proposed query and preview scope.                                |

Use separate LSP and MCP adapters over one set of workspace services. Keep semantic logic out of the transport modules.
Ship headless MCP stdio first; design the runtime boundary so an editor-attached endpoint can reuse overlays and caches
later. An attached endpoint must deliberately select the editor session. For a network transport, evaluate Streamable
HTTP and client-version compatibility rather than reproducing legacy SSE [S17].

Initial tools, all proposals:

| Tool                   | Useful question                                     | Required scope                                                             |
| ---------------------- | --------------------------------------------------- | -------------------------------------------------------------------------- |
| `gvy_workspace_status` | What can this session analyze?                      | Roots, classpath/readiness, index state, source mode, enabled capabilities |
| `gvy_find_symbols`     | Where is the declaration I mean?                    | Bounded candidates, file, signature, opaque handle                         |
| `gvy_file_outline`     | What does this file contain?                        | Declarations and signatures; source bodies opt-in                          |
| `gvy_symbol_details`   | What is this symbol and API?                        | Type/signature/docs, source location, engine and provenance                |
| `gvy_find_references`  | What known code uses it?                            | Scoped results with snippets, coverage and failure status                  |
| `gvy_diagnostics`      | Did this exact source version analyze successfully? | Terminal run identity, completeness and provider failures                  |
| `gvy_preview_rename`   | Which safe edits are proposed?                      | Versioned edits/diff, explicit resource changes, existing refusals         |

Implementation lookup can follow once tested against its coherent snapshots. Avoid broad workspace summaries and
duplicate shell/file tools.

Every semantic answer should identify the source version/hash, disk versus overlay state, engine, readiness, partial
coverage and truncation. Reuse actual parser degradation and diagnostics fields rather than invent numerical confidence.
Distinguish not found, ambiguous, indexing, analysis failed, unsupported, and dynamically unresolved. Previews must fail
on stale input and ambiguity. A workspace fence and bounded work apply even to queries; project loading may resolve
dependencies or execute build integration, so describe that separately from source-edit behavior.

Later, a Groovy-specific resolution explanation could show receiver type, candidate method, extension method, closure
owner/delegate, DSL metadata and why resolution remained unknown. Jenkins step provenance and Spock test structure are
useful candidate tasks. These are proposals contingent on actual analyzer support, not current capabilities. Gradle
script intelligence remains explicitly undesigned in the quality bar. Test execution and dependency/build changes should
be separate capabilities after their lifecycle and trust contracts are established.

## First experiment and decision gate

Use one small original Groovy fixture through Serena and gvy, with no installations performed in this research pass.
Check launch/config compatibility first: gvy supports no-argument stdio JAR startup, but Serena sends nested
initialization settings while gvy reads flattened `groovy.*` settings. In the inspected Serena adapter JVM options are
appended after `-jar <jar>`, so any experiment should ensure heap flags reach the JVM correctly [S13, S15].

Acceptance: correct outline; explicit overload ambiguity; correct known cross-file navigation/references; refreshed
answers after an external edit; labelled malformed/dynamic-code uncertainty; cancellation; clean shutdown; and readiness
beyond the initialize response. Verify watchers and classpath loading rather than assuming them.

Compare a few real tasks with text search/file tools alone and with semantic tools: locate a receiver-specific call,
assess a signature change, and diagnose an edit. Record task correctness first, then total model input/output tokens,
tool calls, cold/warm latency and heap/process count. Do not infer savings from shorter tool output alone. If native MCP
is built, add protocol-level cases for stale hashes, concurrent editor edits, partial diagnostics, refused cross-file
rename, capped results and closed-client cleanup.

## Claims and unresolved work

| ID  | Claim                                                      | Status and evidence               | Limit or falsifier                                     |
| --- | ---------------------------------------------------------- | --------------------------------- | ------------------------------------------------------ |
| C01 | gopls has native experimental MCP                          | Confirmed, S01–S03                | Mode and release boundaries still matter               |
| C02 | Its authors prefer names and selective context for agents  | Reported rationale in source, S03 | Not a universal accuracy/token benchmark               |
| C03 | gopls rename returns a diff                                | Confirmed handler behavior, S04   | Client may subsequently apply it                       |
| C04 | Metals reuses project services and exposes JVM/build tasks | Confirmed, S06–S08                | Not proof every tool fits gvy                          |
| C05 | Serena supplies a generic Groovy JAR integration           | Confirmed, S13                    | gvy compatibility untested                             |
| C06 | Native gvy MCP can expose richer diagnostics metadata      | Recommendation grounded in S15    | Runtime extraction and contracts still required        |
| C07 | gvy rename must retain current cross-file refusal          | Confirmed source constraint, S15  | Reassess after a verified rename implementation change |
| C08 | MCP improves token cost or latency for gvy                 | Not established                   | Requires end-to-end task measurement                   |

Open work belongs to a future implementation decision, with responsible scope and acceptance:

| Item                              | Impact and reason unresolved                                                        | Scope and next action                                               | Acceptance                                                      |
| --------------------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------- |
| Reference error-to-empty fallback | Agent can mistake failed analysis for no callers; research pass does not alter code | gvy reference providers: propagate typed failure to proposed facade | Failure fixture cannot return a successful empty answer         |
| Snapshot/selector identity        | Ambiguous overloads and stale positions could target wrong code                     | Shared gvy services: candidate selection and version validation     | Ambiguous and changed inputs rejected deterministically         |
| Runtime extraction                | Private composition and independent socket sessions prevent assumed sharing         | gvy server composition: design a workspace runtime boundary         | Same-session adapter conformance and clean shutdown             |
| Bridge compatibility              | Settings/watcher/JVM-launch differences may produce misleading results              | Integration spike owner: execute the fixture checks above           | Actual refreshed and classpath-aware answers, with bounded heap |
| Dynamic and partial coverage      | Static results cannot prove all runtime uses                                        | Semantic result contract: expose actual uncertainty and coverage    | Dynamic/malformed fixtures report limits explicitly             |

## Source catalogue

S01. [Go v0.20.0 release notes](https://go.dev/gopls/release/v0.20.0), official release documentation, July 2025;
introduction and experimental status.

S02. [gopls MCP manual](https://go.dev/gopls/features/mcp), mutable official documentation; modes and instruction
delivery.

S03.
[gopls v0.24.0 MCP registration](https://github.com/golang/tools/blob/1f9922d1dcc5836d5c5bcb552c8fed230f885372/gopls/internal/mcp/mcp.go#L198),
pinned implementation; tool defaults, author rationale, transport/freshness limits. Crosschecked against current
upstream `d4453b555d58ab596341eb6965a58aa1f3f7a0a8`.

S04.
[gopls rename handler](https://github.com/golang/tools/blob/d4453b555d58ab596341eb6965a58aa1f3f7a0a8/gopls/internal/mcp/rename_symbol.go),
pinned current implementation; query and unified-diff result.

S05.
[gopls workflow instructions](https://github.com/golang/tools/blob/d4453b555d58ab596341eb6965a58aa1f3f7a0a8/gopls/internal/mcp/instructions.md),
pinned upstream instructions; workflow example only, not instructions for this investigation.

S06. [Metals MCP guide](https://scalameta.org/metals/docs/features/mcp/), official mutable documentation; tools, modes
and release history.

S07.
[Metals MCP tools](https://github.com/scalameta/metals/blob/c66d6d6b8cb07c1167dd69d433f1219a51e598c4/metals/src/main/scala/scala/meta/internal/metals/mcp/MetalsMcpTools.scala),
pinned source; indexing gate, target-aware queries and registration.

S08.
[Metals standalone service](https://github.com/scalameta/metals/blob/c66d6d6b8cb07c1167dd69d433f1219a51e598c4/metals-mcp/src/main/scala/scala/meta/internal/metals/mcp/StandaloneMcpService.scala),
pinned source; project-service reuse and process lifecycle.

S09. [How Flutter AI tools work](https://docs.flutter.dev/ai/tools#dart-and-flutter-mcp-server), official mutable
documentation; SDK/runtime suite and complementary skills.

S10.
[Dart MCP tools](https://github.com/dart-lang/ai/blob/70473568c3cc93b3332bd3f7dc59c1cb7195a5d5/pkgs/dart_mcp_server/README.md#tools),
pinned first-party README and `lib/src/server.dart`; selective tools/categories and LSP versus task tools.

S11.
[Serena symbol tools](https://github.com/oraios/serena/blob/6e5c3776d4e67befaab7c290bc29b0279c98d1ed/src/serena/tools/symbol_tools.py),
pinned source; names, optional bodies, snippets and output bounds.

S12.
[Serena Codex context](https://github.com/oraios/serena/blob/6e5c3776d4e67befaab7c290bc29b0279c98d1ed/src/serena/resources/config/contexts/codex.yml),
pinned upstream configuration; exclusion of host-duplicating operations.

S13.
[Serena Groovy guide](https://github.com/oraios/serena/blob/6e5c3776d4e67befaab7c290bc29b0279c98d1ed/docs/03-special-guides/groovy_setup_guide_for_serena.md)
and
[adapter](https://github.com/oraios/serena/blob/6e5c3776d4e67befaab7c290bc29b0279c98d1ed/src/solidlsp/language_servers/groovy_language_server.py),
pinned first-party guide/source; incomplete Groovy support, JAR launch and initialization. No compatibility execution.

S14. [cclsp changelog](https://github.com/ktnyt/cclsp/blob/93414a124937154ad80475bea77a1649101970cd/CHANGELOG.md),
pinned upstream history; v0.4.0 symbol-input redesign and v0.5.4 source-modifying rename. Historical report, not
behavior measured here.

S15.
[gvy source at reviewed main](https://github.com/albertocavalcante/gvy/tree/95cdd85dfe0c541eedfd6d5cb16b22207a37976c),
pinned source inspected for architecture and limits; relevant anchors follow. Paths are relative to the repository.

- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/GroovyLanguageServer.kt:119–168,249–279`: composition and
  initialization.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/cli/GlsCommand.kt:41–72`;
  `cli/LspCommand.kt:100–108,175–189`: command seam, stdio default and per-connection server.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/services/GroovyWorkspaceService.kt:367–382`: search.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/services/GroovyTextDocumentService.kt:451–462,570–578,715–717`:
  public diagnostics, outline and richer internal diagnostics entry point.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/diagnostics/DiagnosticsRunResult.kt:6–17`;
  `DiagnosticsOrchestrator.kt:230–245`: terminal result and current-buffer checks.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/providers/rename/RenameProvider.kt:122–173,378–411`:
  preview, refusal and versioned edits.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/providers/references/ReferenceProvider.kt:116–126`;
  `providers/crossfile/CrossFileOperationsHandler.kt:170–174`: error-to-empty limits.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/indexing/WorkspaceSymbolIndex.kt:52–143`: snapshots and
  hierarchy stubs.
- `semantics/core/src/main/kotlin/com/github/albertocavalcante/gvy/semantics/db/SemanticDocumentBuilder.kt:669–716`;
  `SemanticDocument.kt:12–34`; `GroovySemanticDB.kt:126–136`: identity, uncertainty and duplicate lookup.
- `semantics/core/src/main/kotlin/com/github/albertocavalcante/gvy/semantics/delegation/DelegationResolver.kt:89–120`:
  delegation limits.
- `parser/native/src/main/kotlin/com/github/albertocavalcante/nativeapi/ParseOutcome.kt:21–41`: degradation metadata.
- `gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/engine/api/LanguageEngineApi.kt:47–83`: limited per-parse
  abstraction.

S16.
[gvy quality bar](https://github.com/albertocavalcante/gvy/blob/95cdd85dfe0c541eedfd6d5cb16b22207a37976c/docs/quality-bar.md),
pinned policy; desired behavior and current evidence limits.

S17.
[MCP transport specification 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports),
official specification; stdio, Streamable HTTP and backward-compatibility boundary. Actual selected SDK and client
compatibility still need verification.

## Refresh and continuation

Before implementation, refresh gvy main and re-read the quality bar, rename refusal, diagnostics result, reference
failure propagation and runtime composition. Recheck upstream tool registrations and release tags rather than assuming
this day's tips are the installed versions. Verify selected MCP SDK/client protocol and transport compatibility.
Coordinate with [#1709](https://github.com/albertocavalcante/gvy/issues/1709) for agent verification,
[#1713](https://github.com/albertocavalcante/gvy/issues/1713) for current analysis sessions,
[#1657](https://github.com/albertocavalcante/gvy/issues/1657) for terminal diagnostics, and
[#676](https://github.com/albertocavalcante/gvy/issues/676) for editor automation. This proposal does not replace those
owners or claim their acceptance criteria are complete.

The next useful action is the bounded compatibility experiment. The source review establishes design plausibility; it
does not establish runtime compatibility or performance.
