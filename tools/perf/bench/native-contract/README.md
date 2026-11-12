# Native Groovy comparison feasibility

Run a small correctness and compatibility probe before collecting benchmark timings. This development-only harness is
not a product parser dependency and does not publish performance numbers.

## Prerequisites and commands

Run this harness on macOS or Linux with an installed Rust toolchain at least 1.90, Cargo, a native C compiler and Node
24 or later. The two packages have independent workspace roots and lockfiles: Cargo forbids linking two versions of the
native `tree-sitter` library in one dependency graph. Do not combine these packages into a single workspace.

From the repository root, run the source verification controls:

```bash
node --test tools/perf/bench/native-contract/*.test.mjs
node tools/perf/bench/native-contract/run.mjs primary --verify-only
node tools/perf/bench/native-contract/run.mjs secondary --verify-only
```

For each package (`primary` or `secondary`), run its native checks and complete feasibility receipt:

```bash
cargo fmt --manifest-path tools/perf/bench/native-contract/primary/Cargo.toml --check
CARGO_BUILD_JOBS=1 cargo clippy --locked --manifest-path tools/perf/bench/native-contract/primary/Cargo.toml --all-targets -- -D warnings
CARGO_BUILD_JOBS=1 cargo test --locked --manifest-path tools/perf/bench/native-contract/primary/Cargo.toml
node tools/perf/bench/native-contract/run.mjs primary
```

Repeat with `secondary` in place of `primary`. The runner fetches locked source dependencies if absent, verifies source
pins, performs a release build with one job, runs the probe and emits one JSON receipt. Setup/build time is not a
benchmark result. Failed source, command, query, output or correctness checks exit nonzero. Run heavy builds only within
the host's coordinated resource allowance; this command does not acquire a host-wide measurement window.

Release-build failures and native-probe failures have separate fixed categories. A probe's known fixed error line can
identify the failed control; unknown, extra or secret-bearing stderr yields only `NATIVE_PROBE_FAILED`. Raw stderr and
its hashes are never exported. Timeout, output-limit and cleanup-uncertainty categories take precedence over a probe
diagnostic. A failure category does not certify completed work.

## Inputs and identity

`source-lock.json` pins the grammar commits, all generated/parser/scanner/header/query/Rust-binding inputs, licenses,
runtime release commits and Cargo lockfile digests. Cargo independently verifies registry package checksums and Git
revisions. The runner checks registry package release provenance through `.cargo_vcs_info.json` before building. Source
inventory changes, a modified generated parser, a missing query or a wrong digest fail verification.

`--verify-only` fetches/verifies source metadata without compiling or certifying native execution.

The primary uses `dekobon/tree-sitter-groovy` with tree-sitter/highlight 0.27.2. The secondary uses
`murtaza64/tree-sitter-groovy` with 0.26.8. These intentionally differ in grammar and runtime; their receipts must not
be used to attribute an isolated grammar effect. The JSON includes actual Rust/Cargo versions, OS/architecture, source
identities, query digests, lock/manifest/binary digests and control outcomes. It omits local cache paths and environment
values. The runner compares the child's actual embedded fixture bytes against the source fixture bytes, then removes
that echo from the final receipt; a stale binary cannot claim a newer same-length fixture identity.

Regular-file reads and process output have byte limits. On macOS/Linux, each command owns a POSIX process group;
timeout, output overflow, cancellation or a leader leaving descendants triggers group termination. Success requires
observed exit/stream closure and no remaining group. Uncertain cleanup fails explicitly. Deliberately escaped sessions
and uninterruptible OS/filesystem calls cannot be contained by this helper. Other platforms are unsupported rather than
silently receiving weaker cleanup.

## Correctness scope

The authored valid fixture exercises both grammar-specific predicates. The primary must distinguish primitive `int` from
ordinary type `String`; the secondary must distinguish constant-shaped `MAX_VALUE` from mixed-case `Max_Value`. The
malformed fixture leaves a parenthesis open. Each grammar must expose an error covering that delimiter or a missing node
at the expected end, and the receipt retains actual error/missing-node ranges.

The official highlighter receives the unchanged upstream highlights, locals and injections queries. The complete event
stream must cover source bytes contiguously, have balanced highlight nesting, valid category indices and UTF-8
boundaries, and satisfy exact predicate spans. Empty or partially consumed output cannot pass. Unknown custom predicates
or properties are rejected before highlighting.

Two limits remain explicit:

- Both upstream locals queries use Neovim-style `local.definition.*` captures that the official highlighter does not
  interpret as its exact `local.definition` special capture. The receipt lists them and does not certify upstream local
  binding parity. No query translation is applied; any adapted policy requires separate tests and identity.
- The upstream injection queries request embedded languages. This probe supplies no additional language grammars; an
  actual injection request is rejected as unsupported. A dedicated comment/doc-comment control proves this boundary. Its
  outer event stream is still fully drained and validated, but is not reported as successful embedded highlighting.

The official highlighter parses source internally. This probe makes no prebuilt-tree or incremental-highlighting claim.
The shared `syntax-contract.json` fixes the later common lexical vocabulary and UTF-8 half-open span representation; raw
event spans remain richer engine-specific categories, including uncolored source. Full corpus, semantic parity, edit
replay, timing, warmup and stable-host evidence belong to subsequent benchmark PRs.
