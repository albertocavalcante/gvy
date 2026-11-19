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

Predicate failures additionally distinguish a closed expected case from the observed category class and exact/inexact
span presence. These diagnostics preserve the failing assertion; they do not change its expected range or category.

All Cargo and rustc calls inside the driver opt into the canonical Rust diagnostic classifier from
`tools/ci/check-rust-diagnostics.mjs`. Original stdout and stderr bytes share the command's output cap. Each nonempty
channel must be valid UTF-8 with a complete final newline and permitted controls before the canonical classifier checks
it. Both channels empty cannot certify a clean Rust command. A zero-exit warning/error or invalid capture fails with a
fixed safe code; no raw diagnostic strings or private-data hashes leave this boundary. The native probe uses its
separate closed failure-envelope mode; the two diagnostic modes cannot be combined.

Command errors retain their existing priority over diagnostic failures. Safe `rustCapture` fields classify only the
observed bytes (`clean`, `diagnostics`, `invalid`, or `empty`); `complete` is true only after uninterrupted stream
closure, normal process exit and observed group disappearance. Timeout, overflow, cancellation, descendants or cleanup
uncertainty cannot certify complete capture, even if the observed prefix has no warning. Outer CI stages retain their
own public build logs and use the same canonical classifier. Driver inner streams remain private.

## Recorded secondary rejection

The pinned stock secondary fails the independent `Max_Value` identifier boundary control. Hosted execution at
`3e5130dee5919fea9cffc67aaa8c0b1de341e317` reproduced `NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE`. It
is a rejected candidate for correctness-qualified timing. Its normal strict invocation remains nonzero. The dependency
investigation and acceptance criteria remain open in [gvy#1847](https://github.com/albertocavalcante/gvy/issues/1847).

To retain this known rejection as feasibility evidence, use `run.mjs secondary --attempt`. This still exits with status
1, but emits an attempt receipt only for that exact failure, after checking the failing binary's actual embedded fixture
bytes. Other failures remain failures and do not produce an accepted rejection. Successful native execution exits with
status 0 and cannot be accepted by the frozen rejection policy. The receipt identifies the two completed controls, the
failed identifier control, and the malformed/highlight/predicate/injection controls not run after that rejection.

The separate policy entry point requires a successful primary receipt, the secondary attempt, its actual observed exit,
and an independently computed SHA256 of the built secondary binary immediately before running it:

```bash
node tools/perf/bench/native-contract/feasibility.mjs primary.json secondary-attempt.json 1 "$secondary_binary_sha256"
```

The caller must observe the strict command's real exit status and pass that value; the literal 1 above illustrates the
only accepted status. CI computes the binary digest separately and observes the command status. The policy freezes
grammar/runtime/query/lockfile/manifest/fixture/probe-source identities in `feasibility-policy.json`. Any drift,
different failure, unexpected success, missing controls or independent binary mismatch fails. An identity change
requires fresh review and execution, not automatic policy regeneration. Source policy does not hash itself, avoiding
recursive hashes.

The binary digest records the executed artifact's identity. This is provenance within the trusted build/CI parent,
assuming no hostile concurrent binary replacement; it is not a reproducible-build assertion or cryptographic proof that
an arbitrary JSON producer executed it. The failure envelope is bounded JSON on native stdout; its fixture echo is
checked and stripped before publication. Raw stderr remains private and unknown errors remain closed categories.

The resulting decision labels primary `passed-seven-controls` and secondary `rejected-candidate`; it does not declare
both grammars compatible. In particular, secondary controls not run after rejection are not certified. Both ordinary
strict commands remain available to detect a later changed result.

Before declaring primary success, the policy independently checks the retained parsed result: contiguous complete UTF-8
source-byte coverage, both exact primary predicate anchors, valid error/missing-node ranges at the malformed delimiter
or EOF, the supported ABI and the declared limitations. Empty or truncated useful output cannot pass merely by claiming
seven completed controls. These checks retain B01's narrow scope; they do not establish full lexical or semantic parity.

## Inputs and identity

`source-lock.json` pins the grammar commits, all generated/parser/scanner/header/query/Rust-binding inputs, licenses,
runtime release commits and Cargo lockfile digests. Cargo independently verifies registry package checksums and Git
revisions. The runner checks registry package release provenance through `.cargo_vcs_info.json` before building. Source
inventory changes, a modified generated parser, a missing query or a wrong digest fail verification.

The probe source identities additionally bind the canonical Rust classifier, its `read-evidence.mjs` dependency and the
statically imported `windows-evidence.mjs` helper. The fixed source map also includes that helper's Windows C source.
Receipt or policy path strings never select files to read. The C source hash does not bind the environment-selected
`GVY_CI_EVIDENCE_READER` executable or certify its compiler or binary. B01's native command runner remains macOS/Linux
only; the Windows reader branch is not qualified by this source inventory. An identity change requires a new final-head
execution receipt; historical native evidence remains bound to its original source closure.

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

Each parser must also expose `Max_Value` as one complete identifier node. Matching rendered color across fragments
cannot substitute for correct token boundaries.

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
