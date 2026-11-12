import assert from 'node:assert/strict';
import test from 'node:test';
import { verifyNativeRejection } from './attempt.mjs';

const code = 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE';
const fixtures = { valid: Buffer.from('abc'), malformed: Buffer.from('(') };
const envelope = () => ({ schemaVersion: 1, scope: 'native-probe-failure', engine: 'secondary', runtimeVersion: '0.26.8', code: 'IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE', fixtureBytes: { valid: [97, 98, 99], malformed: [40] } });
const error = value => Object.assign(new Error(code), { nativeOutput: JSON.stringify(value), nativeExitCode: 1 });

test('rejection verifies the failing binary embedded fixture echo before stripping it', () => {
  assert.deepEqual(verifyNativeRejection(error(envelope()), 'secondary', '0.26.8', fixtures), { failureCode: code, fixtureBytesVerified: true });
});

test('stale same-length fixtures and malformed or mismatched failure envelopes fail closed', () => {
  for (const change of [
    e => { e.fixtureBytes.valid[0] = 120; }, e => { delete e.fixtureBytes; },
    e => { e.engine = 'primary'; }, e => { e.runtimeVersion = '0.27.2'; },
    e => { e.code = 'VALID_PARSE_FAILED'; }, e => { e.privateCanary = 'PRIVATE_CANARY_7821'; },
    e => { e.scope = 'native-feasibility-only'; }, e => { e.schemaVersion = 2; },
  ]) {
    const value = envelope(); change(value);
    assert.throws(() => verifyNativeRejection(error(value), 'secondary', '0.26.8', fixtures));
  }
  for (const message of ['NATIVE_COMMAND_TIMEOUT', 'NATIVE_COMMAND_OUTPUT_LIMIT', 'NATIVE_COMMAND_CLEANUP_UNKNOWN', 'NATIVE_PROBE_FAILED']) {
    assert.throws(() => verifyNativeRejection(Object.assign(new Error(message), { nativeOutput: JSON.stringify(envelope()) }), 'secondary', '0.26.8', fixtures));
  }
  assert.throws(() => verifyNativeRejection(Object.assign(new Error(code), { nativeOutput: 'PRIVATE_CANARY_7821' }), 'secondary', '0.26.8', fixtures));
  for (const exit of [undefined, null, 0, 2]) assert.throws(() => verifyNativeRejection(Object.assign(error(envelope()), { nativeExitCode: exit }), 'secondary', '0.26.8', fixtures));
});
