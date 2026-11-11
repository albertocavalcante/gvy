# Dependency bugs and upstream contributions

Investigate dependency failures, track them in gvy, and prepare useful contributions for upstream maintainers.
OpenRewrite, `rules_kotlin`, Groovy itself, and every other dependency can have bugs. Dependencies are part of the
behaviour gvy must validate under the [Quality Bar](quality-bar.md).

The Groovy compiler is gvy's language authority, but its implementation can still be wrong. Use language documentation,
small executable examples, version comparisons, and independent checks to investigate suspected compiler defects.
Agreement between two features using the same compiler is not independent evidence of correctness.

## Establish what failed

1. Preserve a failing example with expected and actual behaviour. Record the exact dependency versions, resolved
   dependency graph, runtime, configuration, commands, and results. Mark tests that have not run as pending.
2. Inspect gvy's adapters, configuration, post-processing, and dependency interactions alongside the dependency's code.
   An integration error or unsupported version combination can resemble an upstream bug.
3. Reduce the failure to a dependency-only repro where practical. Compare raw dependency output with gvy's output,
   including any workaround. Keep a failing regression test and show that the mitigation makes it pass.
4. State the attribution and its limits. Use **suspected upstream bug** until evidence isolates the failure in the
   dependency. Record **reproduced upstream bug** with its tested versions and environment when that repro fails. Record
   upstream acknowledgement separately; a local reproduction does not imply upstream has accepted the diagnosis.

A result on one pinned version does not establish that the latest release has the same bug. Check newer releases when
practical and record untested versions explicitly. Avoid broad claims about a dependency from one failing construct.

## Track the bug in gvy

Create or update an issue on [gvy's issue board](https://github.com/albertocavalcante/gvy/issues) for each suspected or
confirmed upstream bug. Search existing issues first and reuse the relevant tracker. Follow the
[issue conventions](../.agent/workflows/github-issues.md); keep sensitive repro data outside public issues and Git.

The tracker must record:

- **Evidence and attribution:** minimal input, expected and actual output, reproducible commands, tested versions,
  environment, logs or test links, and remaining uncertainty.
- **Impact and mitigation:** affected gvy behaviour, severity, regression coverage, workaround or pin, and its limits.
  Link the gvy fix and its code TODO to this tracker.
- **Ownership and next action:** responsible person or scope, the reason work remains open, and the next investigation,
  release check, or patch task.
- **Upstream status:** existing upstream links, or an explicit status such as unreported, local draft, awaiting human
  review, approved for submission, submitted, or fixed upstream. Keep this separate from confidence in the diagnosis.
- **Exit criteria:** the upstream version or commit to verify, tests that must pass without the workaround, and the
  steps to upgrade gvy and remove the workaround.

Keep this follow-up open after landing a local workaround. An upstream fix alone is insufficient: verify the fixed
version in gvy, remove obsolete mitigation, and retain the regression test before closing the follow-up.

## Prepare upstream work for human review

Contributing repros, tests, documentation, and patches upstream is part of maintaining healthy dependencies. Evaluate
that option for each reproduced defect. Record the decision and next action in the local tracker; tracking a bug does
not replace considering a contribution.

Agents must never autonomously file upstream issues, open upstream pull requests, or post upstream comments. Routine
authority to publish and merge gvy changes does not authorize actions in dependency repositories. Prepare reports and
patches locally, linked from the gvy tracker, for a human maintainer to review and submit. Any agent submission requires
separate, explicit approval of the exact target and reviewed material.

Before requesting that approval:

1. Search upstream for duplicates, fixes, and release notes. Check the project's current contribution, reporting, and AI
   policies. Record their links and any disclosure requirements. If AI policy is unclear, leave submission pending human
   review; if it prohibits the proposed material, do not submit it.
2. Prepare a concise report with a runnable minimal repro, exact versions, expected and actual behaviour, and evidence
   that separates observations from hypotheses. Remove private data and unsupported claims.
3. For a patch, follow upstream's conventions and licence requirements. Include a regression test that fails before the
   fix, passing relevant checks, and an explanation of compatibility and limitations.
4. Have a human maintainer verify the diagnosis and final material, decide whether and how submission complies with
   upstream policy, and approve the specific submission. Do not conceal AI involvement or evade a project's rules.

Record any eventual submission, review outcome, and released fix in the gvy tracker. Keep local protections until the
fixed dependency passes gvy's checks. An upstream diagnosis does not transfer responsibility for user safety away from
gvy.
