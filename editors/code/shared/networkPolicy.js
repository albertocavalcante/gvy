// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// @ts-check

/**
 * Strict authority parsing precedes URL parsing to avoid WHATWG rewrites of
 * credentials, backslashes, numeric hosts, empty ports and escaped hostnames.
 * IPv6 literals are currently unavailable in the shared policy contract.
 * @param {string} input
 * @param {boolean} [originOnly]
 * @returns {string | null}
 */
function normalizeOrigin(input, originOnly = true) {
  if (typeof input !== "string" || /[\p{Cc}\p{Z}\\]/u.test(input)) return null;
  const match = /^(https?):\/\/([^/?#]+)(.*)$/i.exec(input);
  if (!match) return null;
  const [, rawScheme, authority, suffix] = match;
  if (originOnly ? suffix !== "" && suffix !== "/" : suffix.includes("#"))
    return null;
  const hostPort = /^([a-z0-9.-]+)(?::([0-9]+))?$/i.exec(authority);
  if (!hostPort) return null;
  const scheme = rawScheme.toLowerCase();
  const host = hostPort[1].toLowerCase();
  const labels = host.split(".");
  if (
    host.length > 253 ||
    labels.some(
      (label) =>
        !label ||
        label.length > 63 ||
        label.startsWith("-") ||
        label.endsWith("-"),
    )
  )
    return null;
  const lastLabel = labels[labels.length - 1];
  if (
    /^(?:[0-9]+|0x[0-9a-f]*)$/.test(lastLabel) &&
    (labels.length !== 4 ||
      labels.some(
        (label) => !/^(?:0|[1-9][0-9]*)$/.test(label) || Number(label) > 255,
      ))
  )
    return null;
  const port =
    hostPort[2] === undefined
      ? scheme === "https"
        ? 443
        : 80
      : Number(hostPort[2]);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) return null;
  try {
    // Also reject malformed escapes and URI syntax rejected by the JVM parser.
    if (
      /%(?![0-9a-f]{2})/i.test(suffix) ||
      /[<>"{}|^`]/.test(suffix) ||
      /[\[\]]/.test(suffix.split("?")[0])
    )
      return null;
    new URL(input);
  } catch {
    return null;
  }
  const defaultPort = scheme === "https" ? 443 : 80;
  return `${scheme}://${host}${port === defaultPort ? "" : `:${port}`}`;
}

/** @param {import('./networkPolicy').PolicyFailureCode} code */
function failure(code) {
  return Object.freeze({ code });
}

/**
 * Only a caller-selected trusted authority may supply input. No file discovery.
 * Invalid configuration becomes an invalid cache-only snapshot, never a default
 * connected snapshot. Null represents absent input at this internal boundary.
 * @param {unknown} input
 * @param {number} revision
 * @returns {import('./networkPolicy').NetworkPolicy}
 */
function parsePolicy(input, revision) {
  if (!Number.isSafeInteger(revision) || revision < 0)
    throw new RangeError("Invalid policy revision");
  let valid = true;
  /** @type {'cache-only' | 'connected'} */
  let mode = "cache-only";
  /** @type {string[]} */
  let origins = [];
  if (input !== null && input !== undefined) {
    if (typeof input !== "object" || Array.isArray(input)) {
      valid = false;
    } else {
      const obj = /** @type {Record<string, unknown>} */ (input);
      valid = Object.keys(obj).every(
        (key) => key === "mode" || key === "allowedOrigins",
      );
      if (Object.hasOwn(obj, "mode")) {
        if (obj.mode === "cache-only" || obj.mode === "connected")
          mode = obj.mode;
        else valid = false;
      }
      if (Object.hasOwn(obj, "allowedOrigins")) {
        if (!Array.isArray(obj.allowedOrigins)) valid = false;
        else
          for (const value of obj.allowedOrigins) {
            const origin =
              typeof value === "string" ? normalizeOrigin(value) : null;
            if (origin === null) valid = false;
            else origins.push(origin);
          }
      }
    }
  }
  if (!valid) {
    mode = "cache-only";
    origins = [];
  }
  const allowedOrigins = Object.freeze([...new Set(origins)]);
  const snapshot = {
    revision,
    valid,
    mode,
    allowedOrigins,
    /** @param {string} url */
    authorize(url) {
      if (!valid) return failure("INVALID_CONFIGURATION");
      if (mode === "cache-only") return failure("CACHE_ONLY");
      const origin = normalizeOrigin(url, false);
      if (origin === null) return failure("INVALID_URL");
      return allowedOrigins.includes(origin)
        ? null
        : failure("ORIGIN_NOT_ALLOWED");
    },
  };
  return Object.freeze(snapshot);
}

/** Atomic synchronous admission/publication fencing for one Node event loop. */
class NetworkPolicyController {
  /** @type {import('./networkPolicy').NetworkPolicy} */
  #snapshot;
  /** @type {Set<{cancel: () => void}>} */
  #operations = new Set();

  /** @param {unknown} [initial] */
  constructor(initial) {
    this.#snapshot = parsePolicy(initial, 0);
  }

  get snapshot() {
    return this.#snapshot;
  }

  /**
   * Publish the new revision before invoking any reentrant transport callbacks.
   * @param {unknown} input
   * @returns {unknown[]} Cancellation errors, retained for caller diagnostics.
   */
  replace(input) {
    const next = parsePolicy(input, this.#snapshot.revision + 1);
    this.#snapshot = next;
    const cancelled = [...this.#operations];
    this.#operations.clear();
    const errors = [];
    for (const operation of cancelled) {
      try {
        operation.cancel();
      } catch (error) {
        errors.push(error);
      }
    }
    return errors;
  }

  /**
   * Callers must register before starting a transport and close on acknowledged
   * completion. Publication actions must be synchronous and nonblocking.
   * @param {string} url
   * @param {() => void} cancel
   * @returns {import('./networkPolicy').PolicyAdmission | import('./networkPolicy').PolicyFailure}
   */
  admit(url, cancel) {
    const denied = this.#snapshot.authorize(url);
    if (denied) return denied;
    const revision = this.#snapshot.revision;
    const operation = { cancel };
    this.#operations.add(operation);
    const isCurrent = () =>
      revision === this.#snapshot.revision && this.#operations.has(operation);
    return Object.freeze({
      revision,
      isCurrent,
      /** @param {() => void} action */
      publish: (action) => {
        if (!isCurrent()) return false;
        action();
        return true;
      },
      close: () => {
        this.#operations.delete(operation);
      },
    });
  }
}

module.exports = { normalizeOrigin, parsePolicy, NetworkPolicyController };
