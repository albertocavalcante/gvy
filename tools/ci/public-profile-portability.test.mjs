import assert from 'node:assert/strict';
import { gzipSync, gunzipSync, inflateRawSync } from 'node:zlib';
import test from 'node:test';
import { MAX_PROFILE_BYTES, projectProfile, validatePublicProfile } from './public-bazel-evidence.mjs';

const canary = 'synthetic-gzip-metadata-canary';
const raw = JSON.stringify({ traceEvents: [{ ph: 'X', name: 'phase', ts: -1, dur: 2, pid: 1, tid: 1 }] });
const clean = () => projectProfile(gzipSync(raw));
function optionalHeader(input, flag) {
  const header = Buffer.from(input.subarray(0, 10));
  header[3] = flag;
  let optional = Buffer.from(canary + '\0');
  if (flag === 4) optional = Buffer.concat([Buffer.from([canary.length, 0]), Buffer.from(canary)]);
  return Buffer.concat([header, optional, input.subarray(10)]);
}

test('different private gzip compression levels and OS markers project to identical public JSON', () => {
  const canonical = clean();
  for (const level of [0, 1, 6, 9]) for (const os of [3, 19, 255]) {
    const input = gzipSync(raw, { level });
    input[9] = os;
    const originalBytes = Buffer.from(input);
    assert.deepEqual(projectProfile(input), canonical);
    assert.deepEqual(input, originalBytes);
    assert.throws(() => validatePublicProfile(input));
  }
  assert.deepEqual(validatePublicProfile(canonical), canonical);
  assert.deepEqual(projectProfile(canonical, { publicInput: true }), canonical);
});

test('private optional gzip headers and empty members cannot survive in public JSON', () => {
  const original = gzipSync(raw);
  const empty = gzipSync(Buffer.alloc(0));
  const sources = [
    ...[4, 8, 16].map(flag => optionalHeader(original, flag)),
    Buffer.concat([original, empty]),
    Buffer.concat([original, optionalHeader(empty, 8)]),
    Buffer.concat([original, optionalHeader(empty, 16)]),
    Buffer.concat([original, Buffer.alloc(16)]),
  ];
  for (const input of sources) {
    assert.deepEqual(gunzipSync(input), Buffer.from(raw));
    const canonical = projectProfile(input);
    assert.deepEqual(canonical, clean());
    assert.equal(canonical.includes(Buffer.from(canary)), false);
    assert.throws(() => validatePublicProfile(input), error => !error.message.includes(canary));
  }
});

test('public validation rejects recoverable ignored deflate padding and projection removes it', () => {
  // This exact canonical public JSON also passes the rejected v1 validator;
  // only the compressed padding distinguishes the adversarial encoding.
  const canonical = Buffer.from('{"traceEvents":[{"name":"Bazel event 1","ph":"X","ts":-1,"dur":2,"pid":1,"tid":1}]}');
  const original = gzipSync(canonical);
  const synthetic = Buffer.from('synthetic-padding-canary');
  const blocks = [];
  // Empty stored blocks have five ignored header padding bits. Exact input
  // consumption, CRC and decoded JSON cannot certify these compressed bytes.
  for (const byte of synthetic) for (const value of [byte & 248, (byte & 7) << 3]) blocks.push(Buffer.from([value, 0, 0, 255, 255]));
  const hidden = Buffer.concat(blocks);
  const input = Buffer.concat([original.subarray(0, 10), hidden, original.subarray(10)]);
  assert.deepEqual(gunzipSync(input), gunzipSync(original));
  const body = input.subarray(10, -8);
  assert.equal(inflateRawSync(body, { info: true }).engine.bytesWritten, body.length);
  const recovered = Buffer.from(Array.from(synthetic, (_, index) => hidden[index * 10] | (hidden[index * 10 + 5] >> 3)));
  assert.deepEqual(recovered, synthetic);
  assert.throws(() => validatePublicProfile(input));
  const projected = projectProfile(input);
  assert.deepEqual(projected, canonical);
  assert.deepEqual(projected, clean());
  assert.equal(projected.includes(synthetic), false);
});

test('malformed private gzip fails closed without a public raw fallback', () => {
  for (const position of [-8, -4]) {
    const input = gzipSync(raw);
    input[input.length + position] ^= 1;
    assert.throws(() => projectProfile(input), /profile/);
  }
  const original = gzipSync(raw);
  assert.throws(() => projectProfile(original.subarray(0, original.length - 1)), /profile/);
  assert.throws(() => projectProfile(Buffer.concat([original, Buffer.from(canary)])), error => !error.message.includes(canary));
});

test('exact public JSON rejects raw args, unknown fields, duplicate keys and noncanonical byte forms', () => {
  const original = clean().toString('utf8');
  const profile = JSON.parse(original);
  profile.traceEvents[0].args = { token: canary };
  assert.throws(() => validatePublicProfile(Buffer.from(JSON.stringify(profile))), /Unprojected/);
  const duplicate = original.replace('{"traceEvents":', '{"traceEvents":' + JSON.stringify({ token: canary }) + ',"traceEvents":');
  assert.deepEqual(JSON.parse(duplicate), JSON.parse(original));
  for (const candidate of [duplicate, original + '\n', original.replace('traceEvents', '\\u0074raceEvents'), JSON.stringify({ ...JSON.parse(original), otherData: canary })]) {
    assert.throws(() => validatePublicProfile(Buffer.from(candidate)), error => !error.message.includes(canary));
  }
});

test('private compressed, decoded and public JSON byte bounds remain explicit', () => {
  assert.throws(() => projectProfile(Buffer.alloc(32 * 1024 * 1024 + 1)), /oversized/);
  const oversized = Buffer.alloc(MAX_PROFILE_BYTES + 1, 0x20);
  assert.throws(() => projectProfile(gzipSync(oversized)), /oversized/);
  assert.throws(() => validatePublicProfile(oversized), /size/);
  assert.throws(() => projectProfile(oversized, { publicInput: true }), /oversized/);
});

test('a small raw profile whose projected labels expand beyond the output bound is rejected', () => {
  const event = { ph: 'X', name: 'x', id: 'x', ts: 0, pid: 0, tid: 0, dur: 0 };
  const source = { traceEvents: Array(1000000).fill(event) };
  const bytes = Buffer.from(JSON.stringify(source));
  assert.ok(bytes.length < MAX_PROFILE_BYTES);
  assert.throws(() => projectProfile(gzipSync(bytes)), /Oversized public profile/);
});
