# Bazel configuration and build evidence

The repository pins Bazel **9.0.0**. Local JSON Build Event Protocol (BEP) and JSON trace profiles provide build
evidence without a remote service. BES and remote-cache upload are **disabled by owner choice**: the base configuration
clears the BES, remote cache and executor destinations and disables uploading local action results. No account, endpoint
or credential is required for local artifacts.

## Profiles and resource budgets

| Profile            | Behavior                                                                                            |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| Base               | Two jobs, two CPU scheduling units, 4096 MB action budget; one worker/request per worker key.       |
| `--config=local`   | Updates `MODULE.bazel.lock` when dependency resolution changes. Review that diff before committing. |
| `--config=ci`      | Rejects lockfile drift, prints detailed test results, and continues collecting errors.              |
| `--config=debug`   | Prints action commands and preserves sandboxes; inspect privately and remove retained files later.  |
| `--config=profile` | Enables JSON trace profiling; pass a unique `--profile=...` destination.                            |
| `--config=bep`     | Publishes action outcomes and waits for local JSON output; pass `--build_event_json_file=...`.      |
| `--config=bes`     | Optional synchronous BES transport policy with a 60-second timeout; enables no destination/upload.  |

The Bazel server heap is capped at 2 GiB and its idle lifetime is ten minutes. Java binaries/tests receive `-Xmx768m`;
Java build tools receive `--host_jvmopt=-Xmx1536m`. A rule's own JVM flags may add or override options, so these are not
a claim that every custom tool obeys one heap setting. Worker limits apply per worker key, including distinct flags and
environments. The 4096 MB scheduling budget is not an operating-system memory limit. Heavy builds on shared Macs still
run through `build-slot --slots 1 -- ...`.

Shared repository and disk caches reuse downloads and action results across worktrees. Disk-cache GC targets 5 GiB and
14 days after one idle minute; collection is asynchronous and the cache can temporarily exceed that size. Repository
archives, extracted repositories, output bases, debug sandboxes and retained evidence need separate disk-space
management. Do not point multiple worktrees at one fixed `--output_base`: Bazel uses one server/output tree per output
base. When relocating output, use `--output_user_root` and let Bazel derive each workspace's separate output base.

`user.bazelrc.template` documents private machine overrides. A home rc file is read before the workspace rc; a private
`user.bazelrc` is imported last. CI should disable home rc loading and keep its command-line resource and remote-off
policy explicit. No profile retries failed tests: `--flaky_test_attempts=1` applies even to rules tagged flaky. A manual
rerun is separate evidence with a new invocation ID and directory.

## Capture local evidence

The same wrapper works locally and in CI. It creates a fresh invocation directory beneath the report root, generates
identity metadata from Git and GitHub's event context, captures both output streams, validates the BEP and checks that
the trace profile exists and is nonempty. It returns the original Bazel failure code; successful Bazel execution with
invalid evidence still fails the wrapper.

```bash
build-slot --slots 1 -- node tools/ci/run-bazel.mjs \
  --report-root "$HOME/.local/state/gvy/build-evidence/public" \
  --private-report-root "$HOME/.local/state/gvy/build-evidence/private" --producer bazel-test \
  test //common:common_test
```

Each invocation directory is named `<producer>-<command>-<uuid>`. The report root is **public-only**. It contains
projected `bep.json`, canonical uncompressed `profile.json`, approved `context.json`, `summary.json` and a versioned
`public-evidence.json` checksum manifest. The separate private root contains raw BEP/profile and `stdout.log`,
`stderr.log`, `console.log`. CI must pass the private root explicitly; locally it defaults to a sibling named
`<report-root>-private`. The roots must be disjoint, including through existing symlinks.

The wrapper uses `--config=ci` by default to reject lockfile drift; add `--config=local` when intentionally updating the
graph. It records whether the worktree was dirty. It computes Git/GitHub identity in the parent, then starts Bazel with
a minimal explicit environment of platform identity, temporary-directory and tool/cache paths. Credential variables,
GitHub context and injected Java options do not inherit. Bazelisk bootstrapping with an action token happens separately,
before the credential-free build invocation. This environment boundary is not a filesystem sandbox.

The wrapper disables the home rc file and forces remote-off settings and identity/artifact flags after user options. A
standalone `--` target separator remains supported. Optional `--startup-arg FLAG` values before `build|test` are
restricted to `--batch`, an explicit `--host_jvm_args=-Xmx...` of at most 4 GiB, and absolute `--output_user_root=...` /
`--output_base=...` paths. The caller owns slot coordination; the wrapper acquires no nested slot.

## Public artifact privacy contract

**gvy is public. Its Actions artifacts must be treated as public.** Raw Bazel evidence can contain unmasked client
environment values, command arguments, credentials, source paths, URLs and test output. Never upload the private root,
raw test logs or diagnostic reports copied from raw logs. Raw console output remains available through normal CI log
handling; diagnostic classification reads the private console files, and those diagnostic reports stay outside the
public upload root.

The versioned `gvy-public-bazel-v2` projection publishes approved identity/timing, four build-metadata fields, true
build/action/test outcomes, cache counts and typed numeric metrics. It removes command-line/options/client-environment
payload contents, started option descriptions, action command lines, arbitrary metadata, output text and artifact URIs.
Every string within graph IDs is mapped consistently to an opaque digest while event kinds, child edges, nested ID
shapes and numeric retry/run/shard fields remain intact. Target, action, runner and file labels are therefore
unavailable in public artifacts; use normal CI diagnostics for actionable target names.

Published trace profiles retain finite numeric timestamps, durations, thread IDs and counters. Source names, metadata
and args are discarded; names are generic ordinal labels, and counter args are regenerated from numeric values only
under fixed synthetic keys. These profiles show timing and resource tracks, with reduced semantic attribution.

Public profile validation compares every original JSON byte against the canonical public projection. The manifest hashes
that same original buffer. Public gzip is rejected: gzip headers, extra members and ignored DEFLATE padding can retain
data that disappears on decoding, and recompression varies between Node/zlib versions and platforms. Artifact archive
compression handles transfer size without adding a compressed profile to the public evidence directory.

Bazel still writes its private raw `profile.json.gz`. Projection accepts at most 32 MiB of private compressed input and
uses [bounded native decompression](https://nodejs.org/download/release/v20.19.0/docs/api/zlib.html#class-options) with
a 128 MiB decoded limit. The public canonical JSON output and auditor each enforce that same 128 MiB limit. No raw gzip
bytes are copied into public evidence, including optional headers and ignored padding in otherwise valid input.

Version 1 roots, legacy `profile.json.gz`, mixed layouts and gzip bytes renamed `profile.json` fail audit. Regenerate
evidence with the version 2 wrapper; the auditor has no compatibility fallback that republishes old files.

Before upload, run:

```bash
node tools/ci/audit-public-bazel-evidence.mjs --report-root "$PUBLIC_EVIDENCE_ROOT"
```

The audit checks every invocation against the projection contract, approved workflow identity, recomputed summary and
file hashes. It rejects stale contexts, unknown files, symlinks, raw fields, malformed/oversized inputs and unsupported
projection versions. Only invocation directories and the generated `public-audit.json` marker are permitted at the root;
aggregate readiness and diagnostic reports belong elsewhere. Upload must depend on audit success. A passing privacy
audit permits publishing a failed build's genuine projected outcomes; it does not make the build pass.

Malformed or over-limit raw evidence never falls back to raw publication. BEP input/output is limited to 256 MiB;
compressed profiles to 32 MiB and decompressed JSON to 128 MiB. Public failures use controlled stage errors without
input snippets. Descriptor-based reads require supported no-follow semantics and fail closed when unavailable; this path
is validated on macOS/Linux, with no unsupported-platform publication fallback.

## Validate and interpret evidence

The checker accepts the following context schema:

```json
{
  "schemaVersion": 1,
  "headSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "testedSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "runId": "123456789",
  "runAttempt": 1,
  "invocationId": "12345678-1234-4234-8234-123456789abc",
  "command": "test"
}
```

`headSha` identifies the PR's source commit; `testedSha` identifies the actual checkout, which may be GitHub's synthetic
merge commit. For local/push runs they normally match. Each command gets a new UUID. The BEP's UUID, command, pinned
Bazel version and all four metadata values must match the context. An existing artifact from another attempt or source
revision cannot satisfy that check. This binding checks producer consistency; it is not cryptographic attestation of a
checkout or a substitute for the workflow's checkout verification.

A valid stream has exactly one start and finish event, all announced child IDs, no duplicate event IDs, and a final
`lastMessage`. `finished.exitCode.code` must match the actual command exit code. The checker fails on malformed or
truncated JSON, absent artifacts/metadata, mismatched identity, failed actions/targets, aborted events, failed/flaky
summaries and retried attempts. A successful `test` invocation must contain test summaries. Incompatible targets with
BEP reason `SKIPPED` are counted and fail the evidence gate even when Bazel exits zero. An intentional incompatible
target needs a reviewed disposition before it can reduce required build/test coverage.

`summary.json` preserves observations even on failure: command/capture/Bazel statuses, stream completeness, action and
target counts, per-target test statuses and cached-run counts, local/remote test-cache attempt counts, and the BEP's
action/cache/runner, timing, memory and target metrics. `actionsExecuted` includes remote-cache hits and excludes local
action-cache hits; it must not be interpreted as a count of fresh compiler executions. Missing metrics remain absent,
not fabricated zero measurements. Raw BEP is bounded to 256 MiB for validation; exceeding that limit fails explicitly.

Public summaries are recomputed from projected BEP and preserve genuine failure verdicts. Raw log references and test
outputs are not archived by the public projection. Existing diagnostic checks still inspect warnings and dependency
advice from the separate private console captures; BEP success does not replace that policy.

Run the checker regressions with:

```bash
node --test tools/ci/check-bazel-evidence.test.mjs tools/ci/run-bazel.test.mjs \
  tools/ci/public-bazel-evidence.test.mjs tools/ci/audit-public-bazel-evidence.test.mjs
```

## Optional BES state

Remote upload remains **disabled by owner choice**. The `bes` profile alone neither configures a backend nor proves a
working connection. There is no verified destination or authentication contract for this project, and no upload was
attempted. Re-enabling it requires a specifically selected private destination, verified authentication (prefer a scoped
credential helper), and an observed successful synchronous upload. Keep local BEP/profile capture enabled if remote
telemetry is later added. Do not paste tokens into rc files or header arguments, use a public demo endpoint, or create
an account/service implicitly.

## Verified flag sources

The resource, worker, profiling, BEP and BES options were checked against the pinned Bazel 9.0.0 binary's
`help build
--long` / `help startup_options --long` and `canonicalize-flags` output. The configuration removes historical
no-op or already-default compatibility switches, commented arithmetic guesses, speculative remote/RBE examples and fixed
artifact filenames. `--profile` already produces JSON tracing; the old extra CPU-profile toggle is unnecessary.

- [Bazel 9 command-line reference](https://bazel.build/versions/9.0.0/reference/command-line-reference)
- [Build Event Protocol](https://bazel.build/remote/bep)
- [JSON trace profiles](https://bazel.build/advanced/performance/json-trace-profile)
- [Module lockfile policy](https://bazel.build/external/lockfile)
