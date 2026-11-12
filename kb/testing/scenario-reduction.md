# Bounded assertion-preserving reduction

The IO-free `reduceScenarioUnits` helper in `tools/perf/scenario-reducer.mjs` searches ordered opaque unit IDs using a
caller-provided replay oracle. This is one supporting phase of
[#1720](https://github.com/albertocavalcante/gvy/issues/1720). It does not capture sessions, launch a server, emit
receipts or establish a complete capture/replay workflow.

Keep the artifact, configuration, prerequisites and check definitions fixed in the replay adapter. Removable IDs may
refer to optional documents, actions or source chunks privately owned by that adapter. Mandatory initialization and the
target check remain outside those units. Never accept a smaller run just because some assertion failed.

The target signature contains a check ID, expectation SHA-256, failure SHA-256, `failureKind: assertion` and
`origin: product`. A trusted adapter must derive these from actual complete run evidence and the same bounded failure
representation on every attempt. Hash equality preserves an observed symptom; it cannot establish the same root cause.
Structural validation here cannot prove the oracle used a real artifact or evaluated the declared check.

## Oracle and terminal outcomes

Pass an async `evaluate(immutableUnitIds, abortSignal)` callback. A failed result contains the complete target signature
plus `state: failed`, `complete: true` and `cleanup: succeeded`. A passing result has `state: passed`, `complete: true`
and `cleanup: succeeded`. An incomplete result has `state: incomplete`, `complete: false` and its actual cleanup state.
Cleanup may be succeeded, failed or unconfirmed. Extra result keys, accessors and invalid combinations are rejected.

The original input must first reproduce the exact target signature. Subsequent complete passing or different-failure
runs are rejected as reductions. Any incomplete run, failed/unconfirmed cleanup, invalid oracle result or oracle error
stops further attempts. The final candidate must reproduce again within the same budget before `confirmed` is true. An
unconfirmed best candidate is a proposal requiring replay, not a faithful minimized reproduction.

Cancellation or expiry aborts the callback signal and stops waiting. Late completion cannot change the returned
decision. An outstanding attempt has unconfirmed cleanup; the adapter still owns its process and cleanup obligation. Do
not start a second reducer against those resources until teardown is independently confirmed. Synchronous callback code
cannot be preempted; a monotonic deadline check rejects a result returned after expiry.

## Bounds and scope

Defaults are 128 oracle attempts and 30 seconds; callers may select at most 1,024 attempts and 60 seconds. Baseline,
search and final confirmation all share those budgets. At most 512 unique ASCII IDs are accepted, each 1–128 characters
from letters, digits, underscore, period, colon and hyphen; their total is capped at 32 KiB. IDs are identifiers, not
private source content. The immutable decision contains the target, best IDs, attempt count, rejection counts,
confirmation flag and terminal/cleanup states. No raw oracle exception is returned.

This greedy chunk-removal search does not promise a globally minimal result, deterministic behavior from a flaky oracle,
or a fixed-artifact pass. The existing harness, canonical receipt writer, real capture adapter and separately verified
candidate-artifact replay remain required #1720 integration. Published reproductions still need a privacy review; this
helper writes no files and publishes nothing.

Run focused controls without a JVM build:

    node --test tools/perf/scenario-reducer.test.mjs
