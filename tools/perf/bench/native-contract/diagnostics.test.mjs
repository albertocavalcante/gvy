import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyNativeFailure } from './diagnostics.mjs';

test('known fixed native failure preserves the exact control classification', () => {
  assert.equal(classifyNativeFailure('NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED\n'), 'NATIVE_PROBE_PREDICATE_ORACLE_FAILED');
  assert.equal(classifyNativeFailure('NATIVE_CONTRACT_FAILED:MALFORMED_LOCATION_FAILED\r\n'), 'NATIVE_PROBE_MALFORMED_LOCATION_FAILED');
  assert.equal(classifyNativeFailure('NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED:SECONDARY_CONSTANT:EXACT_VARIABLE\n'), 'NATIVE_PROBE_PREDICATE_ORACLE_FAILED_SECONDARY_CONSTANT_EXACT_VARIABLE');
});

test('unknown and secret-bearing native output stays a generic fixed error', () => {
  const canary = 'PRIVATE_CANARY_7821';
  for (const stderr of [
    `NATIVE_CONTRACT_FAILED:${canary}\n`,
    'NATIVE_CONTRACT_FAILED:NEW_UNKNOWN_FAILURE\n',
    `NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED:SECONDARY_CONSTANT:${canary}\n`,
    'NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED:SECONDARY_CONSTANT:UNKNOWN_CATEGORY\n',
    `NATIVE_CONTRACT_FAILED:INPUT_INVALID ${canary}\n`,
    `NATIVE_CONTRACT_FAILED:INPUT_INVALID\n${canary}`,
    `${canary}\nNATIVE_CONTRACT_FAILED:INPUT_INVALID\n`,
    'NATIVE_CONTRACT_FAILED:INPUT_INVALID\n\n',
    canary.repeat(20), null,
  ]) {
    const code = classifyNativeFailure(stderr);
    assert.equal(code, 'NATIVE_PROBE_FAILED');
    assert.ok(!code.includes(canary));
  }
});
