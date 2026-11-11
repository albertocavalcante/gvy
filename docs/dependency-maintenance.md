# Temporary dependency maintenance

Temporary forks, patches and resolver escapes require owned retirement work on the
[gvy issue board](https://github.com/albertocavalcante/gvy/issues). A successful cached build does not show that a
workaround is still necessary. The `Dependency Maintenance` workflow checks the checked-out declarations and review
dates on every PR, main push and daily schedule, without a JVM build or dependency resolution.

Run it locally:

```bash
node --test tools/ci/dependency-maintenance.test.mjs
node tools/ci/dependency-maintenance.mjs
node tools/ci/dependency-maintenance.mjs --inventory
```

`--inventory` prints source identifiers and fingerprints for inspection; it does not approve debt or rewrite the ledger.
The normal check fails for missing, changed, duplicate or stale source records, incomplete accountability and overdue
reviews. It reports every record even when dependency caches emit no warnings.

## Review and retirement

`tools/ci/dependency-maintenance.json` pairs each exact source with a record containing:

- A gvy issue URL and responsible maintenance scope.
- The original reason and current upstream status, including the release or other trigger to evaluate.
- The last verification date, evidence links and what was actually checked. Distinguish source/metadata inspection from
  an executed candidate test; a released fix does not mean gvy has adopted it.
- A review due date, next action and observable removal criteria.

Review dates use UTC calendar days and remain valid through that day. A review is due within 90 days of verification;
current records use a one-week follow-up. Do not extend a date or replace a fingerprint just to pass CI. Recheck
releases, run the relevant candidate/repro, update the existing tracker with results and remaining uncertainty, then
obtain independent review of the revised record. A metadata-only review must say that integration remains pending.

Prefer removing a verified obsolete workaround. Delete its exact source pairing when removal lands; delete a whole
record only when all its sources and acceptance criteria are resolved. Keep regressions, immutable historical artifacts
needed by old commits and a documented rollback. No upstream post is authorized by the ledger; follow the
[dependency bug policy](dependency-bugs.md).

## Inventory boundaries

The gate lexically scans checked-in and nonignored new Bazel/Starlark files, including `MODULE.bazel`, `WORKSPACE`,
`*.bazel` and `*.bzl`. It finds module overrides, Maven target overrides, custom `http_file`/`http_archive`/Git
repository calls (including imported aliases), and custom Starlark `download`/`download_and_extract` calls. Every
checked-in `*.patch` is inventoried, including dormant patches. Maven GMM options, missing lock enforcement, disabled
source fetching and missing-checksum tolerance are also explicit sources. Lock enforcement is considered enabled only
with a nonempty literal lock path and literal `fail_if_repin_required = True`; absent, disabled or unknown values remain
inventoried. Official toolchain extension calls such as `go_sdk.download` in `MODULE.bazel` are ordinary toolchain
declarations, outside the custom-download inventory.

Override fingerprints cover their canonical argument tokens and the affected module's declared version. Comments and
formatting do not change those fingerprints. Patch contents are hashed individually. Custom download files are hashed in
full so changes to URL templates, versions, hashes or loop data outside a repository call require review. Maven
reproducibility settings are fingerprinted separately from the unrelated artifact list.

`manualSources` registers explicit first-party workaround paths and anchors, currently OpenRewrite whitespace and
shebang compensation. Their full source files are hashed; a missing anchor or changed file fails. Add new dependency API
workarounds there during review. The scanner does not infer the reason for arbitrary application code, evaluate
Starlark, discover dynamically constructed/indirect calls, or inspect arbitrary network access in shell/Gradle programs.
New source forms require scanner tests before claiming coverage. Exact source pairings provide no wildcard/category
exemptions; independent review must inspect changes to scanner policy and the ledger.

## Current evidence and limits

The issue board remains the follow-up system:
[resolver/GMM and Wire](https://github.com/albertocavalcante/gvy/issues/1029),
[Kotlin logging](https://github.com/albertocavalcante/gvy/issues/1620),
[JUnit](https://github.com/albertocavalcante/gvy/issues/1621),
[formatter literal/comment safety](https://github.com/albertocavalcante/gvy/issues/1524),
[released spacing fixes](https://github.com/albertocavalcante/gvy/issues/1598), and
[shebang compensation](https://github.com/albertocavalcante/gvy/issues/1623). The former logging/JUnit links to
#1031/#1032 were unrelated and are corrected. Four unused duplicate/speculative Gradle resolver patches were removed;
the active patch cases retain individual removal criteria.

The 2026-11-11 audit checked upstream release metadata and exact source, not a clean resolver migration. Released
rules_jvm_external 7.2 preserves repeated Coursier options but still bundles Coursier 2.1.24; stable Coursier 2.1.26
exists. The old assertion that GMM requires a milestone-only release is obsolete. Fileless-artifact guards and cache
changes cover some paths, so they do not prove every local file-key/path/cycle patch obsolete. The existing Google Maven
checksum claim also needs an exact failing artifact: one current annotation jar sidecar returned HTTP 200.

Candidate integration, fresh Linux/macOS graph comparison, exact patch regressions, Maven repinning and representative
runtime tests remain pending in those trackers. A passing static maintenance check proves accountability and inventory
consistency, not that a workaround remains necessary or that a candidate works. Build diagnostics remain separately
enforced by the [CI diagnostic gate](ci-diagnostics.md).
