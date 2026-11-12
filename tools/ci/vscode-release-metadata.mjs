// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto';
import { Agent, request as httpsRequest } from 'node:https';
import { performance } from 'node:perf_hooks';

const maxBodyBytes = 64 * 1024;
const platforms = new Set(['linux-x64', 'linux-arm64', 'darwin', 'darwin-arm64', 'win32-x64-archive', 'win32-arm64-archive']);
const reasons = new Set([
  'invalid-input', 'invalid-proxy', 'proxy-agent-unavailable', 'proxy-agent-failed', 'agent-failed',
  'request-failed', 'response-status', 'response-aborted', 'response-error', 'response-incomplete',
  'body-too-large', 'invalid-utf8', 'invalid-json', 'invalid-release', 'deadline-exceeded', 'idle-timeout', 'cleanup-failed',
]);

/** Safe failures never reuse the SDK's resolution TimeoutError fingerprint. */
export class VscodeReleaseMetadataError extends Error {
  constructor(reason, originalError) {
    const safeReason = reasons.has(reason) ? reason : 'invalid-input';
    super(`VS Code release metadata failed (${safeReason})`);
    this.name = 'VscodeReleaseMetadataError';
    this.code = 'VSCODE_RELEASE_METADATA_ERROR';
    this.reason = safeReason;
    if (originalError !== undefined) {
      let text;
      try { text = originalError instanceof Error ? originalError.message : String(originalError); }
      catch { text = 'unreadable-error'; }
      this.originalErrorSha256 = createHash('sha256').update(text).digest('hex');
    }
  }
}

function validateSelectors(version, platform) {
  const exactVersion = typeof version === 'string' && version.length <= 64 && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version) &&
    version.split('.').every(part => Number.isSafeInteger(Number(part)));
  if (!exactVersion || !platforms.has(platform)) throw new VscodeReleaseMetadataError('invalid-input');
}

/** The API identifies a release; it does not attest to the SDK's transferred bytes. */
export function projectExactVscodeRelease(value, selectors) {
  const { version, platform } = selectors ?? {};
  validateSelectors(version, platform);
  const object = value !== null && typeof value === 'object' && !Array.isArray(value);
  if (!object || !['name', 'productVersion', 'version', 'sha256hash'].every(key => Object.hasOwn(value, key)) ||
      value.name !== version || value.productVersion !== version ||
      typeof value.version !== 'string' || !/^[a-f0-9]{40}$/.test(value.version) ||
      typeof value.sha256hash !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256hash)) {
    throw new VscodeReleaseMetadataError('invalid-release');
  }
  return { version, platform, quality: 'stable', commit: value.version, archiveSha256: value.sha256hash };
}

function configuredProxy(env) {
  if (!env || typeof env !== 'object') throw new VscodeReleaseMetadataError('invalid-input');
  // Match the pinned SDK: the HTTPS-specific npm setting takes precedence,
  // and an empty setting is unset. Other proxy variables are not consulted.
  const proxy = env.npm_config_https_proxy || env.npm_config_proxy;
  if (!proxy) return null;
  try {
    if (typeof proxy !== 'string' || !/^https?:\/\//i.test(proxy) || /[\u0000-\u0020\u007f]/.test(proxy)) throw new Error();
    const url = new URL(proxy);
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) throw new Error();
  } catch {
    throw new VscodeReleaseMetadataError('invalid-proxy');
  }
  return proxy;
}

/**
 * Fetch one bounded official exact-version response. The caller supplies the
 * pinned SDK's supported proxy agent; this module loads no extra dependency.
 * Node inherits NODE_EXTRA_CA_CERTS at startup; no TLS CA override is applied.
 */
export async function fetchExactVscodeRelease(input, options = {}) {
  const { version, platform, budgetMs } = input ?? {};
  validateSelectors(version, platform);
  if (!Number.isSafeInteger(budgetMs) || budgetMs < 1 || budgetMs > 10000 || !options || typeof options !== 'object') {
    throw new VscodeReleaseMetadataError('invalid-input');
  }
  const {
    env = process.env, makeProxyAgent, request = httpsRequest, makeAgent = () => new Agent(),
    now = () => performance.now(), setTimer = setTimeout, clearTimer = clearTimeout,
  } = options;
  const deadline = now() + budgetMs;
  const proxy = configuredProxy(env);
  const url = `https://update.code.visualstudio.com/api/versions/${version}/${platform}/stable`;

  return new Promise((resolve, reject) => {
    let agent, outgoing, incoming, deadlineTimer;
    let settled = false, size = 0;
    let chunks = [];
    const destroyed = new Set();
    const destroy = object => {
      if (!object || destroyed.has(object)) return;
      destroyed.add(object);
      try { object.destroy(); }
      catch (error) { return error; }
    };
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      if (deadlineTimer !== undefined) clearTimer(deadlineTimer);
      let cleanupError;
      for (const object of [incoming, outgoing, agent]) {
        const error = destroy(object);
        cleanupError ??= error;
      }
      chunks = [];
      if (error) reject(error);
      else if (cleanupError) reject(new VscodeReleaseMetadataError('cleanup-failed', cleanupError));
      else resolve(result);
    };
    const fail = (reason, error) => finish(new VscodeReleaseMetadataError(reason, error));
    const remaining = () => {
      const value = deadline - now();
      if (value <= 0) { fail('deadline-exceeded'); return 0; }
      return value;
    };
    const receive = response => {
      // Keep an error listener even after terminal cleanup: a socket can report
      // an error after destroy(), including a response arriving after expiry.
      response.on('error', error => fail('response-error', error));
      if (settled) { destroy(response); return; }
      incoming = response;
      if (!remaining()) return;
      if (response.statusCode !== 200) { fail('response-status'); return; }
      response.on('aborted', () => fail('response-aborted'));
      response.on('close', () => { if (!settled) fail('response-incomplete'); });
      response.on('data', chunk => {
        if (settled || !remaining()) return;
        if (!(chunk instanceof Uint8Array)) { fail('invalid-utf8'); return; }
        if (!chunk.byteLength) return;
        size += chunk.byteLength;
        if (size > maxBodyBytes) { fail('body-too-large'); return; }
        chunks.push(Buffer.from(chunk));
      });
      response.on('end', () => {
        if (settled || !remaining()) return;
        if (response.complete !== true) { fail('response-incomplete'); return; }
        let text, metadata;
        try { text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(Buffer.concat(chunks, size)); }
        catch (error) { fail('invalid-utf8', error); return; }
        try { metadata = JSON.parse(text); }
        catch (error) { fail('invalid-json', error); return; }
        try {
          const result = projectExactVscodeRelease(metadata, { version, platform });
          if (remaining()) finish(null, result);
        } catch (error) {
          if (error instanceof VscodeReleaseMetadataError) finish(error);
          else fail('invalid-release', error);
        }
      });
    };

    deadlineTimer = setTimer(() => fail('deadline-exceeded'), Math.max(1, Math.ceil(deadline - now())));
    if (proxy && typeof makeProxyAgent !== 'function') { fail('proxy-agent-unavailable'); return; }
    try {
      agent = proxy ? makeProxyAgent(proxy) : makeAgent();
      if (!agent || typeof agent.destroy !== 'function') throw new Error('invalid-agent');
    } catch (error) { fail(proxy ? 'proxy-agent-failed' : 'agent-failed', error); return; }
    if (!remaining()) return;
    try {
      outgoing = request(url, { method: 'GET', agent, maxHeaderSize: 16 * 1024 }, receive);
      outgoing.on('error', error => fail('request-failed', error));
      // A test transport may deliver and complete synchronously in request().
      if (settled) { destroy(outgoing); return; }
      const idleMs = Math.min(15000, remaining());
      if (!idleMs) return;
      outgoing.setTimeout(Math.max(1, Math.floor(idleMs)), () => fail('idle-timeout'));
      outgoing.end();
    } catch (error) { fail('request-failed', error); }
  });
}
