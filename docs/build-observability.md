# Bazel configuration and build evidence

The repository pins Bazel **9.0.0**. Local JSON Build Event Protocol (BEP) and JSON trace profiles provide build
evidence without a remote service. Remote upload is **disabled by owner choice**: the base configuration clears the BES,
remote cache and executor destinations and disables uploading local action results. No account, endpoint or credential
is required for local artifacts.

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
  --report-root "$HOME/.local/state/gvy/build-evidence" --producer bazel-test \
  test //common:common_test
```

Each directory is named `<producer>-<command>-<uuid>` and contains `context.json`, `bep.json`, `profile.json.gz`,
`stdout.log`, `stderr.log`, `console.log` and `summary.json`. Preserve failed invocation directories too. The wrapper
uses `--config=ci` by default to reject lockfile drift; add `--config=local` when intentionally updating the graph. It
records whether the worktree was dirty, so local edits are visible as a validation limit.

The wrapper disables the home rc file and forces remote-off settings and identity/artifact flags after user options. A
standalone `--` target separator remains supported. Optional `--startup-arg FLAG` values before `build|test` are
restricted to `--batch`, an explicit `--host_jvm_args=-Xmx...` of at most 4 GiB, and absolute `--output_user_root=...` /
`--output_base=...` paths. No shell expansion or environment dump is involved. The caller owns slot coordination; the
wrapper does not acquire another build slot.

Trace profiles can be opened in a locally installed trace viewer. The checker requires a requested profile to exist and
be nonempty; it does not validate trace semantics or claim that every action produced profiling data.

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
BEP reason `SKIPPED` are counted explicitly; the checker does not assert full coverage of skipped targets.

`summary.json` preserves observations even on failure: command/capture/Bazel statuses, stream completeness, action and
target counts, per-target test statuses and cached-run counts, local/remote test-cache attempt counts, and the BEP's
action/cache/runner, timing, memory and target metrics. `actionsExecuted` includes remote-cache hits and excludes local
action-cache hits; it must not be interpreted as a count of fresh compiler executions. Missing metrics remain absent,
not fabricated zero measurements. Raw BEP is bounded to 256 MiB for validation; exceeding that limit fails explicitly.

The summary does not copy BEP command lines, environment, stdout or artifact URIs. **Raw BEP, trace profiles and console
logs can contain sensitive information**, including source paths, action commands, repository URLs and test output.
Retain them with the repository's private CI artifact access/retention policy. JSON BEP references local test logs;
those references alone do not archive their contents. Existing CI diagnostic checks still inspect warnings and
dependency advice; BEP success does not replace that policy.

Run the checker regressions with:

```bash
node --test tools/ci/check-bazel-evidence.test.mjs tools/ci/run-bazel.test.mjs
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
