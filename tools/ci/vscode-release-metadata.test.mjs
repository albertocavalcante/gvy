// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import { fetchExactVscodeRelease, projectExactVscodeRelease, VscodeReleaseMetadataError } from './vscode-release-metadata.mjs';

const selectors = { version: '1.144.0', platform: 'linux-x64' };
const input = { ...selectors, budgetMs: 100 };
const fixture = {
  name: '1.144.0', productVersion: '1.144.0',
  version: '2c33b50b16c472efe58357d1d2a8a5414bc23fbf',
  sha256hash: '17b72a7e6f40a5b5dce9850f10eea13ce734a63052b67fa021cc01e715ad4cf1',
  url: 'https://PRIVATE.invalid/archive?token=PRIVATE',
};
const expected = { ...selectors, quality: 'stable', commit: fixture.version, archiveSha256: fixture.sha256hash };
const hash = value => createHash('sha256').update(value).digest('hex');

function fakeAgent() {
  return { destroys: 0, destroy() { this.destroys++; } };
}

function transport(script) {
  const requests = [], responses = [], agents = [];
  const options = {
    env: {},
    makeAgent() { const agent = fakeAgent(); agents.push(agent); return agent; },
    request(url, config, receive) {
      const request = new EventEmitter();
      Object.assign(request, { url: String(url), config, destroys: 0, idleMs: null });
      request.destroy = () => { request.destroys++; };
      request.setTimeout = (ms, callback) => { request.idleMs = ms; request.idle = callback; };
      request.end = () => queueMicrotask(() => script({ request, respond }));
      const respond = (statusCode = 200) => {
        const response = new EventEmitter();
        Object.assign(response, { statusCode, complete: false, destroys: 0 });
        response.destroy = () => { response.destroys++; };
        responses.push(response);
        receive(response);
        return response;
      };
      requests.push(request);
      return request;
    },
  };
  return { options, requests, responses, agents };
}

function bodyTransport(bytes, { statusCode = 200, complete = true, chunks } = {}) {
  return transport(({ respond }) => {
    const response = respond(statusCode);
    for (const chunk of chunks ?? [Buffer.from(bytes)]) response.emit('data', chunk);
    response.complete = complete;
    response.emit('end');
  });
}

function assertDestroyed(value) {
  for (const object of [...value.requests, ...value.responses, ...value.agents]) assert.equal(object.destroys, 1);
}

async function rejectsMetadata(promise, reason) {
  await assert.rejects(promise, error => {
    assert.ok(error instanceof VscodeReleaseMetadataError);
    assert.equal(error.code, 'VSCODE_RELEASE_METADATA_ERROR');
    assert.equal(error.reason, reason);
    assert.notEqual(error.name, 'TimeoutError');
    assert.equal(error.cause, undefined);
    assert.ok(!`${error.stack}\n${JSON.stringify(error)}`.includes('PRIVATE'));
    return true;
  });
}

test('official fixture projects only the requested exact stable release identity', async () => {
  const value = bodyTransport(JSON.stringify({ ...fixture, vendor: { secret: 'PRIVATE' } }));
  assert.deepEqual(await fetchExactVscodeRelease(input, value.options), expected);
  assert.deepEqual(projectExactVscodeRelease(fixture, selectors), expected);
  assert.equal(value.requests[0].url, 'https://update.code.visualstudio.com/api/versions/1.144.0/linux-x64/stable');
  assert.equal(value.requests[0].config.method, 'GET');
  assert.equal(value.requests[0].config.agent, value.agents[0]);
  assertDestroyed(value);
});

test('all SDK download platforms use the fixed origin and stable route', async () => {
  for (const platform of ['linux-x64', 'linux-arm64', 'darwin', 'darwin-arm64', 'win32-x64-archive', 'win32-arm64-archive']) {
    const value = bodyTransport(JSON.stringify(fixture));
    assert.deepEqual(await fetchExactVscodeRelease({ ...input, platform }, value.options), { ...expected, platform });
    assert.equal(value.requests[0].url, `https://update.code.visualstudio.com/api/versions/1.144.0/${platform}/stable`);
    assertDestroyed(value);
  }
});

test('exactly 64 KiB of raw UTF-8 JSON is accepted and one excess byte fails', async () => {
  for (const size of [64 * 1024, 64 * 1024 + 1]) {
    const base = { ...fixture, padding: '' };
    const body = JSON.stringify({ ...base, padding: 'x'.repeat(size - Buffer.byteLength(JSON.stringify(base))) });
    assert.equal(Buffer.byteLength(body), size);
    const value = bodyTransport(body);
    if (size === 64 * 1024) assert.deepEqual(await fetchExactVscodeRelease(input, value.options), expected);
    else await rejectsMetadata(fetchExactVscodeRelease(input, value.options), 'body-too-large');
    assertDestroyed(value);
  }
});

test('the byte limit includes all chunks and multi-byte vendor fields', async () => {
  const bytes = Buffer.from(JSON.stringify({ ...fixture, vendor: 'é🦊' }));
  const split = bytes.indexOf(Buffer.from('🦊')) + 2;
  const valid = bodyTransport('', { chunks: [bytes.subarray(0, split), bytes.subarray(split)] });
  assert.deepEqual(await fetchExactVscodeRelease(input, valid.options), expected);
  assertDestroyed(valid);
  const overflow = bodyTransport('', { chunks: [Buffer.alloc(64 * 1024, ' '), Buffer.from('é')] });
  await rejectsMetadata(fetchExactVscodeRelease(input, overflow.options), 'body-too-large');
  assertDestroyed(overflow);
});

test('malformed UTF-8, malformed JSON and a byte-order mark fail safely', async () => {
  for (const [body, reason] of [
    [Buffer.from([0xc0, 0xaf]), 'invalid-utf8'],
    [Buffer.from([0xe2, 0x82]), 'invalid-utf8'],
    [Buffer.from('{"PRIVATE":'), 'invalid-json'],
    [Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(JSON.stringify(fixture))]), 'invalid-json'],
  ]) {
    const value = bodyTransport(body);
    await rejectsMetadata(fetchExactVscodeRelease(input, value.options), reason);
    assertDestroyed(value);
  }
});

test('metadata requires both release names and full lowercase commit and archive digest', async () => {
  const invalid = [null, [], 'PRIVATE', {},
    { ...fixture, name: '1.143.0' }, { ...fixture, productVersion: '1.144.1' },
    ...['name', 'productVersion', 'version', 'sha256hash'].map(key => { const copy = { ...fixture }; delete copy[key]; return copy; }),
    { ...fixture, version: fixture.version.toUpperCase() }, { ...fixture, version: 'a'.repeat(39) },
    { ...fixture, version: 123 }, { ...fixture, sha256hash: fixture.sha256hash.toUpperCase() },
    { ...fixture, sha256hash: 'f'.repeat(63) }, { ...fixture, sha256hash: 123 },
  ];
  for (const metadata of invalid) {
    assert.throws(() => projectExactVscodeRelease(metadata, selectors), VscodeReleaseMetadataError);
    const value = bodyTransport(JSON.stringify(metadata));
    await rejectsMetadata(fetchExactVscodeRelease(input, value.options), 'invalid-release');
    assertDestroyed(value);
  }
  assert.throws(() => projectExactVscodeRelease(Object.create(fixture), selectors), VscodeReleaseMetadataError);
});

test('invalid selectors and budgets cannot start a request or an agent', async () => {
  const patches = [
    ...['stable', 'v1.144.0', '1.144.0-insider', '01.144.0', '1.144.0?PRIVATE', '1.144.0/PRIVATE', '9007199254740992.0.0'].map(version => ({ version })),
    ...['linux-x64/PRIVATE', 'https://PRIVATE.invalid', '', 'linux-ia32'].map(platform => ({ platform })),
    ...[0, -1, 10001, 1.5, NaN, Infinity, '100'].map(budgetMs => ({ budgetMs })),
  ];
  for (const patch of patches) {
    const value = bodyTransport(JSON.stringify(fixture));
    await rejectsMetadata(fetchExactVscodeRelease({ ...input, ...patch }, value.options), 'invalid-input');
    assert.equal(value.requests.length, 0); assert.equal(value.agents.length, 0);
  }
});

test('non-200 responses, including redirects, are destroyed without following Location', async () => {
  for (const statusCode of [301, 302, 303, 307, 308, 404, 500]) {
    const value = transport(({ respond }) => { const response = respond(statusCode); response.headers = { location: 'https://PRIVATE.invalid/' }; });
    await rejectsMetadata(fetchExactVscodeRelease(input, value.options), 'response-status');
    assert.equal(value.requests.length, 1);
    assertDestroyed(value);
  }
});

test('aborted, prematurely closed and incomplete responses fail with complete cleanup', async () => {
  for (const [event, reason] of [['aborted', 'response-aborted'], ['close', 'response-incomplete'], ['end', 'response-incomplete']]) {
    const value = transport(({ respond }) => { const response = respond(); response.emit('data', Buffer.from(JSON.stringify(fixture))); response.emit(event); });
    await rejectsMetadata(fetchExactVscodeRelease(input, value.options), reason);
    assertDestroyed(value);
  }
});

test('request and response errors expose only a digest of private original text', async () => {
  for (const source of ['request', 'response']) {
    const original = new Error('https://PRIVATE-user:PRIVATE-password@PRIVATE.invalid/?PRIVATE');
    const value = transport(({ request, respond }) => {
      const emitter = source === 'response' ? respond() : request;
      emitter.emit('error', original);
    });
    await assert.rejects(fetchExactVscodeRelease(input, value.options), error => {
      assert.ok(error instanceof VscodeReleaseMetadataError);
      assert.equal(error.reason, source === 'request' ? 'request-failed' : 'response-error');
      assert.equal(error.originalErrorSha256, hash(original.message));
      assert.ok(!`${error.stack}\n${JSON.stringify(error)}`.includes('PRIVATE'));
      return true;
    });
    assertDestroyed(value);
  }
});

test('idle timeout is capped by the remaining budget and has a distinct safe failure', async () => {
  const value = transport(({ request }) => request.idle());
  await rejectsMetadata(fetchExactVscodeRelease(input, value.options), 'idle-timeout');
  assert.ok(value.requests[0].idleMs > 0 && value.requests[0].idleMs <= input.budgetMs && value.requests[0].idleMs <= 15000);
  assertDestroyed(value);
});

test('absolute deadline covers DNS or a connection that never returns a response', { timeout: 1000 }, async () => {
  const value = transport(() => {});
  const started = performance.now();
  await rejectsMetadata(fetchExactVscodeRelease({ ...input, budgetMs: 20 }, value.options), 'deadline-exceeded');
  assert.ok(performance.now() - started < 750);
  assertDestroyed(value);
});

test('dripping response data cannot reset the absolute deadline', { timeout: 1000 }, async t => {
  let interval, chunks = 0;
  t.after(() => clearInterval(interval));
  const value = transport(({ respond }) => {
    const response = respond();
    response.emit('data', Buffer.from(' ')); chunks++;
    interval = setInterval(() => { response.emit('data', Buffer.from(' ')); chunks++; }, 3);
  });
  const started = performance.now();
  await rejectsMetadata(fetchExactVscodeRelease({ ...input, budgetMs: 35 }, value.options), 'deadline-exceeded');
  clearInterval(interval);
  assert.ok(chunks > 1);
  assert.ok(performance.now() - started < 750);
  assertDestroyed(value);
});

test('monotonic deadline rejects late agent construction and late body completion', async () => {
  let clock = 0;
  const construction = bodyTransport(JSON.stringify(fixture));
  const makeAgent = construction.options.makeAgent;
  await rejectsMetadata(fetchExactVscodeRelease(input, { ...construction.options, now: () => clock, makeAgent() { const agent = makeAgent(); clock = 101; return agent; } }), 'deadline-exceeded');
  assert.equal(construction.requests.length, 0); assertDestroyed(construction);
  clock = 0;
  const body = transport(({ respond }) => {
    const response = respond(); response.emit('data', Buffer.from(JSON.stringify(fixture)));
    clock = 101; response.complete = true; response.emit('end');
  });
  await rejectsMetadata(fetchExactVscodeRelease(input, { ...body.options, now: () => clock }), 'deadline-exceeded');
  assertDestroyed(body);
});

test('SDK proxy precedence and empty-as-unset routing preserve proxy credentials privately', async () => {
  const primary = 'https://PRIVATE-user:PRIVATE-password@proxy.invalid:8443';
  const fallback = 'http://other.invalid:8080';
  for (const [env, selected] of [
    [{ npm_config_https_proxy: primary, npm_config_proxy: fallback }, primary],
    [{ npm_config_https_proxy: '', npm_config_proxy: fallback }, fallback],
    [{ npm_config_proxy: fallback }, fallback],
    [{ npm_config_https_proxy: 'HTTP://PRIVATE-user:PRIVATE-password@proxy.invalid:8080' }, 'HTTP://PRIVATE-user:PRIVATE-password@proxy.invalid:8080'],
    [{ npm_config_https_proxy: '', npm_config_proxy: '', NODE_EXTRA_CA_CERTS: 'PRIVATE-ca-path' }, null],
  ]) {
    const value = bodyTransport(JSON.stringify(fixture));
    const proxies = [], proxyAgents = [];
    const result = await fetchExactVscodeRelease(input, { ...value.options, env, makeProxyAgent(proxy) { proxies.push(proxy); const agent = fakeAgent(); proxyAgents.push(agent); return agent; } });
    assert.deepEqual(result, expected);
    assert.deepEqual(proxies, selected ? [selected] : []);
    assert.equal(value.agents.length, selected ? 0 : 1);
    assert.equal(value.requests[0].config.agent, selected ? proxyAgents[0] : value.agents[0]);
    for (const agent of proxyAgents) assert.equal(agent.destroys, 1);
    assertDestroyed(value);
  }
});

test('invalid or unsupported configured proxy cannot fall back to a direct request', async () => {
  for (const proxy of ['socks5://PRIVATE.invalid', 'http://', 'PRIVATE', 'file:///PRIVATE', ' http://PRIVATE.invalid', 'http://PRIVATE.invalid:70000', 'http://PRIVATE.invalid\n', 123]) {
    const value = bodyTransport(JSON.stringify(fixture));
    let proxyCalls = 0;
    await rejectsMetadata(fetchExactVscodeRelease(input, { ...value.options, env: { npm_config_https_proxy: proxy, npm_config_proxy: 'http://fallback.invalid' }, makeProxyAgent() { proxyCalls++; return fakeAgent(); } }), 'invalid-proxy');
    assert.equal(proxyCalls, 0); assert.equal(value.requests.length, 0); assert.equal(value.agents.length, 0);
  }
});

test('unavailable or failed proxy agent fails before any request and never exposes userinfo', async () => {
  for (const makeProxyAgent of [undefined, () => { throw new Error('PRIVATE-user:PRIVATE-password@PRIVATE.invalid'); }, () => undefined]) {
    const value = bodyTransport(JSON.stringify(fixture));
    await rejectsMetadata(fetchExactVscodeRelease(input, { ...value.options, env: { npm_config_https_proxy: 'http://PRIVATE-user:PRIVATE-password@proxy.invalid' }, makeProxyAgent }), makeProxyAgent ? 'proxy-agent-failed' : 'proxy-agent-unavailable');
    assert.equal(value.requests.length, 0); assert.equal(value.agents.length, 0);
  }
});

test('synchronous request failure destroys its agent and retains no unsafe cause', async () => {
  const agent = fakeAgent();
  await rejectsMetadata(fetchExactVscodeRelease(input, { env: {}, makeAgent: () => agent, request() { throw new Error('PRIVATE request failure'); } }), 'request-failed');
  assert.equal(agent.destroys, 1);
});

test('response arriving after the deadline is still destroyed and late errors are absorbed', { timeout: 1000 }, async () => {
  let receive;
  const value = transport(control => { receive = control.respond; });
  await rejectsMetadata(fetchExactVscodeRelease({ ...input, budgetMs: 15 }, value.options), 'deadline-exceeded');
  const response = receive();
  response.emit('error', new Error('PRIVATE late error'));
  value.requests[0].emit('error', new Error('PRIVATE late error'));
  assertDestroyed(value);
});

test('synchronously supplied response also destroys the returned request', async () => {
  const value = bodyTransport(JSON.stringify(fixture));
  const originalRequest = value.options.request;
  value.options.request = (url, config, receive) => {
    const request = originalRequest(url, config, receive);
    request.end = () => {};
    const response = new EventEmitter();
    Object.assign(response, { statusCode: 200, complete: true, destroys: 0, destroy() { this.destroys++; } });
    value.responses.push(response); receive(response);
    response.emit('data', Buffer.from(JSON.stringify(fixture))); response.emit('end');
    return request;
  };
  assert.deepEqual(await fetchExactVscodeRelease(input, value.options), expected);
  assertDestroyed(value);
});

test('a failed response destructor cannot prevent request and agent cleanup', async () => {
  const value = transport(({ respond }) => {
    const response = respond();
    response.destroy = () => { response.destroys++; throw new Error('PRIVATE destroy failure'); };
    response.emit('data', Buffer.from(JSON.stringify(fixture))); response.complete = true; response.emit('end');
  });
  await rejectsMetadata(fetchExactVscodeRelease(input, value.options), 'cleanup-failed');
  assertDestroyed(value);
});

test('success and every representative failure cancel their owned deadline timer', async () => {
  for (const [body, statusCode, reason] of [[JSON.stringify(fixture), 200, null], ['PRIVATE', 200, 'invalid-json'], ['{}', 200, 'invalid-release'], ['', 302, 'response-status']]) {
    const value = bodyTransport(body, { statusCode });
    const timers = [], cleared = [];
    const promise = fetchExactVscodeRelease(input, { ...value.options, now: () => 0,
      setTimer(callback, ms) { const timer = { callback, ms }; timers.push(timer); return timer; },
      clearTimer(timer) { cleared.push(timer); },
    });
    if (reason) await rejectsMetadata(promise, reason);
    else assert.deepEqual(await promise, expected);
    assert.equal(timers.length, 1); assert.equal(timers[0].ms, input.budgetMs);
    assert.deepEqual(cleared, timers);
    assertDestroyed(value);
  }
});
