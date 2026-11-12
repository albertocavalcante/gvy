import { validatePrimaryResult } from './primary-result.mjs';

const controls = ['valid-parse', 'exact-variable-identifier-boundary', 'malformed-error-location', 'complete-highlight-event-stream', 'regex-positive-negative', 'unknown-predicate-rejected', 'embedded-injection-explicitly-unsupported'];
const canonical = value => JSON.stringify(value, (_, item) => item && typeof item === 'object' && !Array.isArray(item) ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);
const equal = (a, b) => canonical(a) === canonical(b);
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

/** Trusted CI must run the producer and supply its real exit and separately observed binary hash. */
export function validateFeasibility(primary, secondary, observed, policy) {
  const fail = () => { throw new Error('FEASIBILITY_POLICY_MISMATCH'); };
  if (policy?.schemaVersion !== 1 || policy.policyId !== 'b01-stock-secondary-rejected-v1') fail();
  for (const [engine, receipt] of [['primary', primary], ['secondary', secondary]]) {
    if (receipt?.schemaVersion !== 1 || receipt.engine !== engine || !digest(receipt.binarySha256)) fail();
    const expected = policy.identities?.[engine];
    if (!expected || Object.keys(expected).sort().join(',') !== 'cargoLockSha256,fixtureDigests,grammar,manifestSha256,probeSourceDigests,queryDigests,runtime') fail();
    for (const [key, value] of Object.entries(expected)) if (!equal(receipt[key], value)) fail();
  }
  if (primary.scope !== 'native-feasibility-only' || primary.native?.schemaVersion !== 1 || primary.native.scope !== 'native-feasibility-only' || primary.native.engine !== 'primary' || primary.native.runtimeVersion !== primary.runtime[0].version || primary.native.queries !== 'upstream-unchanged' || primary.native.controlCount !== 7 || !equal(primary.native.controls, controls)) fail();
  validatePrimaryResult(primary.native, policy.nativeExpectations?.fixtures, policy.nativeExpectations?.primary, primary.fixtureDigests);
  if (secondary.scope !== 'native-feasibility-attempt' || secondary.outcome !== 'rejected-candidate' || secondary.failureCode !== 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE' || secondary.fixtureBytesVerified !== true || secondary.failedControl !== 'exact-variable-identifier-boundary' || !equal(secondary.completedControls, ['unknown-predicate-rejected', 'valid-parse']) || !equal(secondary.notRunAfterRejection, controls.filter(name => !['valid-parse', 'exact-variable-identifier-boundary', 'unknown-predicate-rejected'].includes(name)))) fail();
  if (observed?.secondaryExitCode !== 1 || !digest(observed.secondaryBinarySha256) || observed.secondaryBinarySha256 !== secondary.binarySha256) fail();
  return { schemaVersion: 1, scope: 'native-feasibility-decision', policyId: policy.policyId, primary: 'passed-seven-controls', secondary: 'rejected-candidate', failureCode: secondary.failureCode, performance: 'not-measured' };
}
