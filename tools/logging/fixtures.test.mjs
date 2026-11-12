import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { childFixture, sourceFixture } from './fixtures.mjs';

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

test('source fixtures are deterministic, valid-shaped and distinct', () => {
  for (const kind of ['groovy', 'spock']) {
    const small = sourceFixture(kind, 'small');
    const large = sourceFixture(kind, 'large');
    assert.equal(small, sourceFixture(kind, 'small'));
    assert.ok(large.length > small.length);
    assert.match(small, /class /);
    assert.match(large, /method999/);
    assert.notEqual(sha256(small), sha256(large));
  }
  assert.throws(() => sourceFixture('invalid', 'small'));
  assert.throws(() => sourceFixture('groovy', 'invalid'));
});

test('child fixture emits exact bytes even across UTF-8 and record boundaries', () => {
  for (const mode of ['burst', 'no-newline', 'unicode']) {
    const result = spawnSync(process.execPath, ['tools/logging/fixtures.mjs', mode], { maxBuffer: 1024 * 1024 });
    assert.equal(result.status, 0, result.stderr.toString());
    assert.deepEqual(result.stdout, childFixture(mode));
  }
  assert.ok(childFixture('no-newline').length > 64 * 1024);
  assert.equal(childFixture('no-newline').includes(10), false);
  assert.equal(childFixture('unicode').toString('utf8'), 'café 🧪\r\n終わり\n');
  assert.throws(() => childFixture('invalid'));
});

test('measurement counts emitted bytes and records without assuming pipe chunks', async () => {
  const { measureChild } = await import('./measure.mjs');
  for (const mode of ['burst', 'no-newline', 'unicode']) {
    const result = await measureChild(mode);
    const expected = childFixture(mode);
    assert.equal(result.bytes, expected.length);
    assert.equal(result.sha256, sha256(expected));
    assert.equal(result.records, mode === 'burst' ? 1000 : mode === 'unicode' ? 2 : 1);
    assert.equal(result.terminated, mode !== 'no-newline');
    assert.equal(result.producerWrites, Math.ceil(expected.length / 7));
    assert.ok(result.readableChunks > 0);
    assert.equal(result.flushCalls, null);
  }
});

test('fixture byte hashes are frozen for comparable runs', () => {
  const expectedSources = {
    'groovy/small': '3563c860082dde801d0f938ba763fd1c1321e555663e683687bb86fa0ee97daf',
    'groovy/large': '91c7d96824e0ef64789e1bee9b7877bf034d2739e0c51a5ca413b7d80b1ab927',
    'spock/small': 'ea9f6a266b7fd4219a1bd31c6899e64a18ba723f2c849c1b2607159b6b80eb41',
    'spock/large': '29878ff3cced37be7329a698eedc6f533dd49d5d9dcbf9dbc235c59000b5c1fd',
  };
  for (const [name, expected] of Object.entries(expectedSources)) {
    const [kind, size] = name.split('/');
    assert.equal(sha256(sourceFixture(kind, size)), expected);
  }
  const expectedChildren = {
    burst: 'd160b3b75541907fa8842174d32c7993bc384535efa7381ba24f0f4148c641b6',
    'no-newline': '15601535eca4a38b7e31ad6494861121cb9f84ccf55d4beb6a707d4f7a87813d',
    unicode: '462571792adecdf5ee8a4a5081c4323deb9d57400f28b2981533529c0f0ca2c1',
  };
  for (const [mode, expected] of Object.entries(expectedChildren)) {
    assert.equal(sha256(childFixture(mode)), expected);
  }
});
