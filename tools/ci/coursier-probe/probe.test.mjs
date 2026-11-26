// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { normalize, historical, compareGraph } from './graph.mjs';
import { fixtureVerdict } from './fixture.mjs';

const graph = {
  version: '0.1.0', conflict_resolution: {}, dependencies: [
    { coord: 'demo:lib:1', directDependencies: ['demo:bom:1'], dependencies: ['demo:bom:1'], file: '/one/v1/https/host/maven2/demo/lib/1/lib-1.jar' },
    { coord: 'demo:bom:1', directDependencies: [], dependencies: [] },
  ],
};
const sum = '1'.repeat(64);
const digest = () => sum;
test('comparison preserves edges, classifier/file identity and metadata-only nodes', async () => {
  const first = await normalize(graph, '/one/v1', digest);
  const copy = structuredClone(graph);
  copy.dependencies.reverse();
  copy.dependencies[1].file = '/two/v1/https/other/maven2/demo/lib/1/lib-1.jar';
  const second = await normalize(copy, '/two/v1', digest);
  assert.deepEqual(first, second);
  assert.equal(first.dependencies[0].file, null);
  copy.dependencies[1].directDependencies = [];
  assert.notDeepEqual(first, await normalize(copy, '/two/v1', digest));
  assert.notDeepEqual(first, await normalize(graph, '/one/v1', () => '2'.repeat(64)));
});
test('rejects incompatible schemas, missing edges, unexplained protocol and empty graph', async () => {
  for (const change of [g => { g.version = '1'; }, g => { g.field = 1; }, g => { g.dependencies = []; },
    g => { delete g.dependencies[0].directDependencies; }, g => { g.dependencies[0].file = '/else/lib.jar'; }]) {
    const g = structuredClone(graph);
    change(g);
    await assert.rejects(normalize(g, '/one/v1', digest));
  }
});
test('historical list reproduces host stripping with duplicate relative paths and final LF', () => {
  assert.equal(historical(['a/maven2/X.jar', 'b/maven2/X.jar', 'b/maven2/a.pom', 'b/x.sha1']), 'maven2/X.jar\nmaven2/X.jar\nmaven2/a.pom\n');
});
test('parity reports exact added/deleted entries even with duplicate coordinates', () => {
  const first = { dependencies: [{ coord: 'a', file: 1 }, { coord: 'a', file: 1 }], conflicts: {} };
  const second = { dependencies: [{ coord: 'a', file: 1 }], conflicts: {} };
  assert.deepEqual(compareGraph(first, second).removed, [{ coord: 'a', file: 1 }]);
});
test('fixture rejects generic failures, timeouts and missed sidecars as evidence', () => {
  const requests = [
    { method: 'GET', path: '/fixture/probe/1/probe-1.jar', status: 200 },
    { method: 'GET', path: '/fixture/probe/1/probe-1.jar.sha1', status: 200 },
  ];
  const rejected = { code: 1, signal: null, stderr: 'wrong checksum: probe-1.jar' };
  assert.equal(fixtureVerdict('mismatch', rejected, requests, false), 'pass');
  assert.equal(fixtureVerdict('mismatch', { ...rejected, stderr: 'invalid option' }, requests, false), 'inconclusive');
  assert.equal(fixtureVerdict('mismatch', { ...rejected, signal: 'SIGTERM' }, requests, false), 'inconclusive');
  assert.equal(fixtureVerdict('mismatch', rejected, [], false), 'inconclusive');
  assert.equal(fixtureVerdict('mismatch', { code: 0, signal: null }, requests, true), 'fail');
  assert.equal(fixtureVerdict('valid', { code: 0, signal: null }, requests, false), 'inconclusive');
  assert.equal(fixtureVerdict('missing', { code: 0, signal: null }, requests.slice(0, 1), true), 'fail');
  for (const sep of ['\n', '\r']) {
    const stderr = 'downloaded probe-1.jar' + sep + 'wrong checksum: probe-1.pom';
    assert.equal(fixtureVerdict('mismatch', { ...rejected, stderr }, requests, false), 'inconclusive');
  }
});
