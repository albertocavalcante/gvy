// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import { vscodeTestVersion, vscodeTestSdkPatchSha256 } from '../../editors/code/tools/vscode-test-config.mjs';
import { digest } from './vscode-provisioning-policy.mjs';
import { validateSdkDownloadSource } from './vscode-provisioning-worker.mjs';

const requireEditor = createRequire(new URL('../../editors/code/package.json', import.meta.url));
const sdkPath = requireEditor.resolve('@vscode/test-electron');
const requireSdk = createRequire(sdkPath);
const sdkRequest = requireSdk('./request');
const sdkUtil = requireSdk('./util');
const sdkProgress = requireSdk('./progress.js');
const release = { name: vscodeTestVersion, productVersion: vscodeTestVersion, version: 'c'.repeat(40), sha256hash: 'e'.repeat(64) };

test('installed SDK source and declared patch match the reviewed identity before provisioning', () => {
  const source = readFileSync(join(dirname(sdkPath), 'download.js'), 'utf8');
  assert.doesNotThrow(() => validateSdkDownloadSource(source));
  assert.throws(() => validateSdkDownloadSource(source + '\n'), /unsupported-sdk-patch/);
  const patch = readFileSync(new URL('../../editors/code/patches/@vscode__test-electron@3.1.0.patch', import.meta.url));
  assert.equal(digest(patch.toString('utf8').replace(/\r\n/g, '\n')), vscodeTestSdkPatchSha256, 'pnpm hashes normalized patch text');
});

function fixture(t, { body = Buffer.from(JSON.stringify(release)), status = 200, complete = true, deliver, legacyValue, transportError, routeError, responseDestroyError, Controller = sdkRequest.TimeoutController } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'gvy-sdk-patch-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const legacy = [], calls = [], events = [];
  const route = { agent: { privateRoute: true } };
  const request = new EventEmitter(), response = new EventEmitter();
  const timers = [];
  t.after(() => timers.forEach(clearInterval));
  request.destroy = () => { request.destroyed = true; };
  response.destroy = () => { response.destroyed = true; timers.forEach(clearInterval); if (responseDestroyError) throw responseDestroyError; };
  response.statusCode = status; response.complete = complete;
  const https = { request(url, options, callback) {
    if (transportError) throw transportError;
    calls.push({ url, options });
    request.end = () => queueMicrotask(() => {
      callback(response);
      if (deliver) deliver({ request, response, timers });
      else { response.emit('data', body); response.emit('end'); }
    });
    return request;
  } };
  const sdk = requireEditor('proxyquire').noCallThru().noPreserveCache()(join(dirname(sdkPath), 'download.js'), {
    https,
    './request': { ...sdkRequest, TimeoutController: Controller, getJSON: async url => { legacy.push(url); if (legacyValue) return legacyValue; throw new sdkRequest.TimeoutError(15000); } },
    './util': { ...sdkUtil, urlToOptions: () => { if (routeError) throw routeError; return route; } },
    './progress.js': sdkProgress,
  });
  const resolved = new Error('stop after validated resolution; do not download an archive');
  const options = { version: vscodeTestVersion, platform: 'linux-x64', cachePath: join(root, 'fresh'), timeout: 15000,
    reporter: { report(event) { events.push(event); if (event.stage === 'resolvedVersion') throw resolved; } } };
  return { sdk, options, resolved, legacy, calls, events, route, request, response };
}

test('installed SDK exact stable validation avoids the stalled release listing and preserves private routing', async t => {
  const value = fixture(t);
  await assert.rejects(value.sdk.download(value.options), error => error === value.resolved);
  assert.deepEqual(value.legacy, []);
  assert.equal(value.calls.length, 1);
  assert.equal(value.calls[0].url, `https://update.code.visualstudio.com/api/versions/${vscodeTestVersion}/linux-x64/stable`);
  assert.equal(value.calls[0].options.agent, value.route.agent);
  assert.deepEqual(value.calls[0].options, { ...value.route, method: 'GET', maxHeaderSize: 16 * 1024 });
  assert.deepEqual(value.events, [{ stage: 'resolvedVersion', version: vscodeTestVersion }]);
  assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
});

test('installed SDK rejects mismatched exact-release metadata before any archive or reporter event', async t => {
  const value = fixture(t, { body: Buffer.from(JSON.stringify({ ...release, productVersion: '1.143.0' })) });
  await assert.rejects(value.sdk.download(value.options), /Invalid version/);
  assert.deepEqual(value.legacy, []); assert.deepEqual(value.events, []);
  assert.equal(value.calls.length, 1); assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
});

test('exact stable resolution preserves each supported platform selector', async t => {
  for (const platform of ['darwin', 'darwin-arm64', 'win32-x64-archive', 'win32-arm64-archive', 'linux-x64', 'linux-arm64', 'linux-armhf']) {
    const value = fixture(t); value.options.platform = platform;
    await assert.rejects(value.sdk.download(value.options), error => error === value.resolved);
    assert.equal(value.calls[0].url, `https://update.code.visualstudio.com/api/versions/${vscodeTestVersion}/${platform}/stable`);
    assert.deepEqual(value.legacy, []);
  }
});

test('unknown selectors and invalid budgets cannot start an exact metadata request', async t => {
  for (const change of [{ platform: 'linux-x64/private?query' }, { platform: 'https://private.invalid' }, { timeout: 0 }, { timeout: Infinity }, { timeout: '15000' }]) {
    const value = fixture(t); Object.assign(value.options, change);
    await assert.rejects(value.sdk.download(value.options), /Invalid version/);
    assert.deepEqual(value.calls, []); assert.deepEqual(value.legacy, []); assert.deepEqual(value.events, []);
  }
});

test('aliases and unreleased selectors keep their original SDK resolution semantics', async t => {
  for (const version of ['stable', `${vscodeTestVersion}-unreleased`, 'insiders', 'c'.repeat(40)]) {
    const value = fixture(t, { legacyValue: [vscodeTestVersion] }); value.options.version = version;
    await assert.rejects(value.sdk.download(value.options), error => error === value.resolved);
    assert.deepEqual(value.calls, []);
    const expected = version === 'stable' ? 'true' : version.endsWith('-unreleased') ? 'false' : null;
    assert.deepEqual(value.legacy, expected ? [`https://update.code.visualstudio.com/api/releases/stable?released=${expected}`] : []);
  }
});

test('schema failures retain literal version validation and cannot fall back to the release list', async t => {
  for (const metadata of [null, [], {}, { ...release, name: '1.143.0' }, { ...release, version: 'c'.repeat(39) }, { ...release, version: 'C'.repeat(40) }, { ...release, sha256hash: 'e'.repeat(63) }]) {
    const value = fixture(t, { body: Buffer.from(JSON.stringify(metadata)) });
    await assert.rejects(value.sdk.download(value.options), /Invalid version/);
    assert.deepEqual(value.events, []); assert.deepEqual(value.legacy, []);
    assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
  }
});

test('64KiB is a raw UTF8 byte bound and over-limit bodies fail without truncation', async t => {
  const empty = JSON.stringify({ ...release, padding: '' });
  const remaining = 65536 - Buffer.byteLength(empty);
  const padding = 'é'.repeat(Math.floor(remaining / 2)) + (remaining % 2 ? 'x' : '');
  const body = Buffer.from(JSON.stringify({ ...release, padding }));
  assert.ok(Buffer.byteLength(padding) > padding.length);
  assert.equal(body.length, 65536);
  const healthy = fixture(t, { body });
  await assert.rejects(healthy.sdk.download(healthy.options), error => error === healthy.resolved);
  const overflow = fixture(t, { body: Buffer.concat([body, Buffer.from(' ')]) });
  await assert.rejects(overflow.sdk.download(overflow.options), /body too large/);
  assert.deepEqual(overflow.events, []); assert.equal(overflow.request.destroyed, true); assert.equal(overflow.response.destroyed, true);
});

test('malformed UTF8, non-byte chunks and invalid JSON cannot approve a release', async t => {
  for (const body of [Buffer.from([0xc3, 0x28]), Buffer.from('{'), 'not bytes']) {
    const value = fixture(t, { body });
    await assert.rejects(value.sdk.download(value.options));
    assert.deepEqual(value.events, []); assert.deepEqual(value.legacy, []);
    assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
  }
});

test('non-200 responses never follow a redirect or retry through the broad release endpoint', async t => {
  for (const status of [301, 302, 403, 500]) {
    const value = fixture(t, { status });
    await assert.rejects(value.sdk.download(value.options), /response status/);
    assert.equal(value.calls.length, 1); assert.deepEqual(value.legacy, []); assert.deepEqual(value.events, []);
    assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
  }
});

test('truncated and aborted responses fail before reporter or archive work', async t => {
  for (const options of [{ complete: false }, { deliver: ({ response }) => response.emit('aborted') }, { deliver: ({ response }) => response.emit('close') }]) {
    const value = fixture(t, options);
    await assert.rejects(value.sdk.download(value.options), /response (incomplete|aborted)/);
    assert.deepEqual(value.events, []); assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true);
  }
});

test('SDK idle timeout keeps its exact constructor while an absolute dripping deadline is distinct', async t => {
  let controller;
  class Controller {
    constructor(timeout) { this.timeout = timeout; this.ctrl = new AbortController(); this.signal = this.ctrl.signal; controller = this; }
    touch() {}
    dispose() { this.disposed = true; }
  }
  const idle = fixture(t, { Controller, deliver: () => controller.ctrl.abort() });
  await assert.rejects(idle.sdk.download(idle.options), error => error instanceof sdkRequest.TimeoutError && error.message === '@vscode/test-electron request timeout out after 15000ms');
  assert.equal(controller.timeout, 15000); assert.equal(controller.disposed, true);
  const dripping = fixture(t, { deliver: ({ response, timers }) => timers.push(setInterval(() => response.emit('data', Buffer.from(' ')), 5)) });
  dripping.options.timeout = 100;
  const started = performance.now();
  await assert.rejects(dripping.sdk.download(dripping.options), error => !(error instanceof sdkRequest.TimeoutError) && /deadline exceeded/.test(error.message));
  assert.ok(performance.now() - started < 2000);
  assert.equal(dripping.request.destroyed, true); assert.equal(dripping.response.destroyed, true);
});

test('private route and transport errors propagate without a direct fallback', async t => {
  for (const key of ['routeError', 'transportError']) {
    const original = new Error('private route failure control');
    const value = fixture(t, { [key]: original });
    await assert.rejects(value.sdk.download(value.options), error => error === original);
    assert.deepEqual(value.events, []); assert.deepEqual(value.legacy, []); assert.deepEqual(value.calls, []);
  }
});

test('cleanup failure cannot retain a retryable idle-timeout classification and preserves its original error', async t => {
  let controller;
  class Controller {
    constructor() { this.ctrl = new AbortController(); this.signal = this.ctrl.signal; controller = this; }
    touch() {}
    dispose() {}
  }
  const value = fixture(t, { Controller, responseDestroyError: new Error('fixture response destructor failed'), deliver: () => controller.ctrl.abort() });
  await assert.rejects(value.sdk.download(value.options), error => error instanceof AggregateError && !(error instanceof sdkRequest.TimeoutError) &&
    error.errors.some(error => error instanceof sdkRequest.TimeoutError) && error.stack.includes('request timeout out after 15000ms'));
  assert.equal(value.request.destroyed, true); assert.equal(value.response.destroyed, true); assert.deepEqual(value.events, []);
});
