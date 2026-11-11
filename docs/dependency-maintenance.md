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
inventoried. Triple-quoted, raw and escaped lock strings are treated as unknown. Checksum enforcement uses the
documented rules_jvm_external 6.10 safe default when omitted; any present value other than literal `True` remains
inventoried by its exact tokens. Official toolchain extension calls such as `go_sdk.download` in `MODULE.bazel` are
ordinary toolchain declarations, outside the custom-download inventory.

Override fingerprints cover their canonical argument tokens and the affected module's declared version. Comments and
formatting do not change those fingerprints. Patch contents are hashed individually. Custom download files are hashed in
full so changes to URL templates, versions, hashes or loop data outside a repository call require review. Lock, checksum
and source-fetch settings are fingerprinted separately from the artifact list. Active GMM evidence is graph-specific:
its fingerprint covers the whole canonical `maven.install` argument body, so artifact-version/module, exclusion,
resolver or option changes require fresh review. This does not evaluate the graph or cover every external contribution.

`manualSources` registers explicit first-party workaround paths and anchors, currently OpenRewrite whitespace,
declaration, literal-value and shebang compensation. Their full source files are hashed; a missing anchor or changed
file fails. Add new dependency API workarounds there during review. The scanner does not infer the reason for arbitrary
application code, evaluate Starlark, discover dynamically constructed/indirect calls, or inspect arbitrary network
access in shell/Gradle programs. New source forms require scanner tests before claiming coverage. Exact source pairings
provide no wildcard/category exemptions; independent review must inspect changes to scanner policy and the ledger.

## Current evidence and limits

The issue board remains the follow-up system:
[resolver/GMM and Wire](https://github.com/albertocavalcante/gvy/issues/1029),
[Kotlin logging](https://github.com/albertocavalcante/gvy/issues/1620),
[JUnit](https://github.com/albertocavalcante/gvy/issues/1621),
[landed formatter literal/comment safety fix](https://github.com/albertocavalcante/gvy/pull/1593),
[released spacing fixes](https://github.com/albertocavalcante/gvy/issues/1598), and
[shebang compensation](https://github.com/albertocavalcante/gvy/issues/1623). The former logging/JUnit links to
#1031/#1032 were unrelated and are corrected. Four unused duplicate/speculative Gradle resolver patches were removed;
the active patch cases retain individual removal criteria.

The temporary rules_kotlin fork was retired in [#1642](https://github.com/albertocavalcante/gvy/pull/1642), adopting
official 2.3.0 and its required rules_jvm_external 6.10. The repeated-option patch context was refreshed and a confirmed
first-party Jackson dependency skip was fixed with isolated Kotlin/YAML runtime regressions. Current Jackson 2.21.2
evidence is bounded to the eight inspected modules; other KMP/BOM filters still need isolated checks. The separate pnpm
inspection and Bazel provenance gaps remain in [#1644](https://github.com/albertocavalcante/gvy/issues/1644) and
[#1563](https://github.com/albertocavalcante/gvy/issues/1563).

The 2026-11-11 stable-resolver audit checked upstream release metadata and exact source, not a 7.2/stable-Coursier
replacement experiment. Released rules_jvm_external 7.2 preserves repeated Coursier options but still bundles Coursier
2.1.24; stable Coursier 2.1.26 exists. The old assertion that GMM requires a milestone-only release is obsolete.
Fileless-artifact guards and cache changes cover some paths, so they do not prove every local file-key/path/cycle patch
obsolete. The existing Google Maven checksum claim also needs an exact failing artifact: one current annotation jar
sidecar returned HTTP 200.

The 8.79.0 formatter integration adopts a released as-cast fix while retaining scoped declaration/parameter
compatibility and literal-value restoration. Independent dependency-only probes reproduce triple-double literal
corruption in 8.79.0 with both declared and gvy Groovy runtimes; a newer fixed release has not been established. The
local parameter-separator adaptation is not independently attributed to raw upstream.

Stable-resolver replacement, fresh Linux/macOS graph comparison, remaining exact patch regressions and Maven repinning
remain pending in those trackers. A passing static maintenance check proves accountability and inventory consistency,
not that a workaround remains necessary or that a candidate works. Build diagnostics remain separately enforced by the
[CI diagnostic gate](ci-diagnostics.md).

Custom `.bzl` source edits, including comments, also change Bazel extension digests. Regenerate the committed module
lock with the affected repositories in update mode, then replay them in error mode. Do not copy digest values manually
or call a failed eager module inspection successful.
