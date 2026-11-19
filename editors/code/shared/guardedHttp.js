// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// @ts-check

const http = require("node:http");
const https = require("node:https");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const { performance } = require("node:perf_hooks");
const { AbortController, AbortSignal } = globalThis;
const { NetworkPolicyController, normalizeOrigin } = require("./networkPolicy");

const MAX_BYTES = 64 * 1024 * 1024;
const MAX_TIMEOUT_MS = 120_000;
const MAX_REDIRECTS = 20;
const forbidden =
  /^(?:host|connection|content-length|transfer-encoding|te|trailer|upgrade|proxy-.*|keep-alive|set-cookie)$/i;
const sensitive = /^(?:authorization|cookie)$/i;

class GuardedHttpError extends Error {
  /** @param {string} code */
  constructor(code) {
    super(code);
    this.name = "GuardedHttpError";
    this.code = code;
    /** @type {string | undefined} */
    this.cleanupPath = undefined;
  }
}
/** @param {string} code */
const fail = (code) => new GuardedHttpError(code);

/** @param {Readonly<Record<string, string>> | undefined} input
 * @param {boolean} credentials
 * @returns {Record<string, string>}
 */
function headerBag(input, credentials = false) {
  const out = Object.create(null);
  if (input == null) return out;
  if (typeof input !== "object" || Array.isArray(input))
    throw fail("INVALID_OPTIONS");
  for (const [name, value] of Object.entries(input)) {
    if (
      typeof value !== "string" ||
      forbidden.test(name) ||
      (!credentials && sensitive.test(name))
    )
      throw fail("INVALID_OPTIONS");
    try {
      http.validateHeaderName(name);
      http.validateHeaderValue(name, value);
    } catch {
      throw fail("INVALID_OPTIONS");
    }
    const key = name.toLowerCase();
    if (Object.hasOwn(out, key)) throw fail("INVALID_OPTIONS");
    out[key] = value;
  }
  return out;
}

/** @param {import('./guardedHttp').GuardedHttpOptions} options */
function configuration(options) {
  if (!options || !(options.controller instanceof NetworkPolicyController))
    throw fail("INVALID_CONFIGURATION");
  const { controller, timeoutMs, maxBytes, signal, ca } = options;
  const maxRedirects = options.maxRedirects ?? 5;
  if (
    !Number.isSafeInteger(timeoutMs) ||
    timeoutMs < 1 ||
    timeoutMs > MAX_TIMEOUT_MS ||
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 1 ||
    maxBytes > MAX_BYTES ||
    !Number.isSafeInteger(maxRedirects) ||
    maxRedirects < 0 ||
    maxRedirects > MAX_REDIRECTS ||
    (signal !== undefined && !(signal instanceof AbortSignal))
  )
    throw fail("INVALID_OPTIONS");
  const headers = headerBag(options.headers);
  const credentialHeaders = headerBag(options.credentials?.headers, true);
  const credentialOrigin = options.credentials?.origin;
  if (
    options.credentials &&
    (typeof credentialOrigin !== "string" ||
      normalizeOrigin(credentialOrigin) !== credentialOrigin)
  )
    throw fail("INVALID_OPTIONS");
  for (const name of Object.keys(credentialHeaders))
    if (Object.hasOwn(headers, name)) throw fail("INVALID_OPTIONS");
  return {
    controller,
    timeoutMs,
    maxBytes,
    maxRedirects,
    signal,
    ca,
    headers,
    credentialHeaders,
    credentialOrigin,
  };
}

/** @param {string} url
 * @param {Record<string, string>} headers
 * @param {AbortSignal} signal
 * @param {import('./guardedHttp').GuardedHttpOptions['ca']} ca
 * @returns {Promise<{response: import('node:http').IncomingMessage, requestClosed: Promise<void>}>}
 */
function requestHop(url, headers, signal, ca) {
  return new Promise((resolve, reject) => {
    const client = new URL(url).protocol === "https:" ? https : http;
    let settled = false;
    let upgraded = false;
    let requestIsClosed = false;
    let socketIsClosed = false;
    /** @type {() => void} */
    let markRequestClosed;
    /** @type {Promise<void>} */
    const requestClosed = new Promise((resolve) => {
      markRequestClosed = resolve;
    });
    /** @type {unknown} */
    let pendingError;
    /** @type {import('node:stream').Duplex | undefined} */
    let upgradeSocket;
    /** @param {unknown} error
     * @param {import('node:http').IncomingMessage | undefined} [response]
     */
    const finish = (error, response) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener("abort", onAbort);
      if (error) reject(error);
      else if (response) resolve({ response, requestClosed });
    };
    const rejectAfterClose = () => {
      if (!settled && requestIsClosed && (!upgraded || socketIsClosed)) {
        finish(pendingError || fail("REQUEST_CLOSED"));
      }
    };
    const onAbort = () => {
      pendingError = signal.reason;
      if (upgraded) upgradeSocket?.destroy();
      else req.destroy();
    };
    const req = client.get(
      url,
      { headers, signal, ...(ca === undefined ? {} : { ca }) },
      (response) => finish(null, response),
    );
    signal.addEventListener("abort", onAbort, { once: true });
    if (signal.aborted) onAbort();
    req.on("error", (error) => {
      pendingError = pendingError || error;
    });
    req.on("close", () => {
      requestIsClosed = true;
      markRequestClosed();
      rejectAfterClose();
    });
    req.on("upgrade", (_response, socket) => {
      upgraded = true;
      upgradeSocket = socket;
      pendingError = fail("UNEXPECTED_UPGRADE");
      socket.once("close", () => {
        socketIsClosed = true;
        rejectAfterClose();
      });
      socket.destroy();
    });
  });
}

/** @param {import('node:http').IncomingMessage} response */
async function discard(response) {
  if (response.closed) return;
  const closed = new Promise((resolve) => response.once("close", resolve));
  if (!response.destroyed) response.destroy();
  await closed;
}

/** @param {import('node:http').IncomingMessage} response
 * @param {number} limit
 */
async function bodyBytes(response, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of response) {
    size += chunk.length;
    if (size > limit) {
      response.destroy();
      throw fail("BODY_TOO_LARGE");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

/** @template T
 * @param {string} input
 * @param {import('./guardedHttp').GuardedHttpOptions} options
 * @param {(bytes: Buffer, admission: import('./networkPolicy').PolicyAdmission, signal: AbortSignal, deadline: number) => Promise<T>} consume
 * @param {boolean} committedResult
 * @returns {Promise<T>}
 */
async function execute(input, options, consume, committedResult = false) {
  const config = configuration(options);
  const initialOrigin = normalizeOrigin(input, false);
  if (!initialOrigin) throw fail("INVALID_URL");
  const operation = new AbortController();
  const deadline = performance.now() + config.timeoutMs;
  const timer = setTimeout(
    () => operation.abort(fail("TIMEOUT")),
    config.timeoutMs,
  );
  const abort = () => operation.abort(fail("ABORTED"));
  if (config.signal?.aborted) abort();
  else config.signal?.addEventListener("abort", abort, { once: true });
  const revision = config.controller.snapshot.revision;
  let url = input;
  let admission;
  try {
    for (let redirects = 0; ; redirects++) {
      if (performance.now() >= deadline) throw fail("TIMEOUT");
      if (operation.signal.aborted) throw operation.signal.reason;
      if (config.controller.snapshot.revision !== revision)
        throw fail("STALE_POLICY");
      const origin = normalizeOrigin(url, false);
      if (!origin) throw fail("INVALID_URL");
      const result = config.controller.admit(url, () =>
        operation.abort(fail("STALE_POLICY")),
      );
      if ("code" in result) throw fail(result.code);
      admission = result;
      const headers = {
        ...(origin === initialOrigin ? config.headers : {}),
        ...(origin === config.credentialOrigin ? config.credentialHeaders : {}),
      };
      const { response, requestClosed } = await requestHop(
        url,
        headers,
        operation.signal,
        config.ca,
      );
      response.on("error", () => {});
      const status = response.statusCode ?? 0;
      if ([301, 302, 303, 307, 308].includes(status)) {
        const location = response.headers.location;
        await discard(response);
        await requestClosed;
        if (typeof location !== "string" || /[\p{Cc}\p{Z}\\#]/u.test(location))
          throw fail("INVALID_URL");
        if (redirects >= config.maxRedirects) throw fail("TOO_MANY_REDIRECTS");
        if (
          location.startsWith("//") &&
          !normalizeOrigin(new URL(url).protocol + location, false)
        )
          throw fail("INVALID_URL");
        if (
          /^[a-z][a-z0-9+.-]*:/i.test(location) &&
          !/^[a-z][a-z0-9+.-]*:\/\//i.test(location)
        )
          throw fail("INVALID_URL");
        if (
          /^[a-z][a-z0-9+.-]*:\/\//i.test(location) &&
          !normalizeOrigin(location, false)
        )
          throw fail("INVALID_URL");
        let next;
        try {
          next = new URL(location, url).href;
        } catch {
          throw fail("INVALID_URL");
        }
        if (/^https:\/\//i.test(url) && /^http:\/\//i.test(next))
          throw fail("TLS_DOWNGRADE");
        if (!normalizeOrigin(next, false)) throw fail("INVALID_URL");
        admission.close();
        admission = undefined;
        url = next;
        continue;
      }
      if (status < 200 || status >= 300) {
        await discard(response);
        await requestClosed;
        throw fail("HTTP_STATUS");
      }
      let bytes;
      try {
        bytes = await bodyBytes(response, config.maxBytes);
      } finally {
        await discard(response);
        await requestClosed;
      }
      if (performance.now() >= deadline) throw fail("TIMEOUT");
      if (operation.signal.aborted) throw operation.signal.reason;
      if (!admission.isCurrent()) throw fail("STALE_POLICY");
      const value = await consume(bytes, admission, operation.signal, deadline);
      if (!committedResult) {
        if (performance.now() >= deadline) throw fail("TIMEOUT");
        if (operation.signal.aborted) throw operation.signal.reason;
        if (!admission.isCurrent()) throw fail("STALE_POLICY");
      }
      return value;
    }
  } catch (error) {
    if (error instanceof GuardedHttpError && error.code === "CLEANUP_FAILED")
      throw error;
    if (operation.signal.aborted) throw operation.signal.reason;
    throw error;
  } finally {
    admission?.close();
    clearTimeout(timer);
    config.signal?.removeEventListener("abort", abort);
  }
}

/** @param {string} url
 * @param {import('./guardedHttp').GuardedHttpOptions} options
 */
function getText(url, options) {
  return execute(url, options, async (bytes, admission, signal, deadline) => {
    const value = bytes.toString("utf8");
    if (performance.now() >= deadline) throw fail("TIMEOUT");
    if (signal.aborted || !admission.isCurrent()) throw fail("STALE_POLICY");
    return value;
  });
}
/** @param {string} url
 * @param {import('./guardedHttp').GuardedHttpOptions} options
 */
function getJson(url, options) {
  return execute(url, options, async (bytes, admission, signal, deadline) => {
    let value;
    try {
      value = JSON.parse(bytes.toString("utf8"));
    } catch {
      if (performance.now() >= deadline) throw fail("TIMEOUT");
      throw fail("INVALID_JSON");
    }
    if (performance.now() >= deadline) throw fail("TIMEOUT");
    if (signal.aborted || !admission.isCurrent()) throw fail("STALE_POLICY");
    return value;
  });
}
/** @param {string} url
 * @param {string} destination
 * @param {import('./guardedHttp').GuardedHttpOptions} options
 */
async function downloadFile(url, destination, options) {
  if (typeof destination !== "string" || !path.isAbsolute(destination))
    throw fail("INVALID_OPTIONS");
  return execute(
    url,
    options,
    async (bytes, admission, signal, deadline) => {
      const directory = await fsp.mkdtemp(
        path.join(path.dirname(destination), ".gvy-download-"),
      );
      const temporary = path.join(directory, "payload");
      let committed = false;
      let failed = false;
      /** @type {unknown} */
      let failure;
      try {
        await fsp.writeFile(temporary, bytes, { flag: "wx", mode: 0o600 });
        if (performance.now() >= deadline) throw fail("TIMEOUT");
        if (signal.aborted) throw signal.reason;
        const published = admission.publish(() => {
          if (signal.aborted) throw signal.reason;
          fs.renameSync(temporary, destination);
          committed = true;
        });
        if (!published) throw fail("STALE_POLICY");
      } catch (error) {
        failed = true;
        failure = error;
      }
      try {
        await fsp.rm(directory, { recursive: true, force: true });
      } catch {
        if (committed)
          return { committed: true, cleanupError: { path: directory } };
        const cleanupFailure = fail("CLEANUP_FAILED");
        cleanupFailure.cleanupPath = directory;
        if (failed) cleanupFailure.cause = failure;
        throw cleanupFailure;
      }
      if (failed) throw failure;
      return { committed: true };
    },
    true,
  );
}
module.exports = { GuardedHttpError, getText, getJson, downloadFile };
