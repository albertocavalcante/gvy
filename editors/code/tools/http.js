const https = require("node:https");
const fs = require("node:fs");
const { URL } = require("node:url");
const { pipeline } = require("node:stream");
const { promisify } = require("node:util");

const pipelineAsync = promisify(pipeline);

const DEFAULT_USER_AGENT = "vscode-groovy-extension";
const DEFAULT_TIMEOUT_MS = 30000;
const DEFAULT_DOWNLOAD_TIMEOUT_MS = 60000;
const DEFAULT_MAX_REDIRECTS = 10;
const MAX_ERROR_BODY_BYTES = 8192;
const MAX_RESPONSE_BODY_BYTES = 5 * 1024 * 1024;

class HttpError extends Error {
  /**
   * @param {object} options
   * @param {string} options.url
   * @param {number} options.statusCode
   * @param {string} options.statusMessage
   * @param {Record<string, string | string[] | undefined>} options.headers
   * @param {string} [options.bodySnippet]
   */
  constructor({ url, statusCode, statusMessage, headers, bodySnippet }) {
    const suffix = bodySnippet?.trim() ? `\n${bodySnippet.trim()}` : "";
    super(`HTTP ${statusCode}: ${statusMessage}${suffix}`);
    this.name = "HttpError";
    this.url = url;
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
    this.headers = headers || {};
    this.bodySnippet = bodySnippet || "";

    const remaining = headerValue(this.headers, "x-ratelimit-remaining");
    const reset = headerValue(this.headers, "x-ratelimit-reset");
    const limit = headerValue(this.headers, "x-ratelimit-limit");
    this.rateLimit = remaining || reset || limit ? { remaining, reset, limit } : null;
    this.isGitHubRateLimit =
      statusCode === 403 &&
      (remaining === "0" ||
        /api rate limit exceeded/i.test(this.bodySnippet || "") ||
        /api rate limit exceeded/i.test(statusMessage || ""));
  }
}

function headerValue(headers, key) {
  const value = headers?.[key.toLowerCase()];
  if (Array.isArray(value)) return value[0];
  if (typeof value === "string") return value;
  return undefined;
}

function isGitHubApiHost(hostname) {
  return (hostname || "").toLowerCase() === "api.github.com";
}

function buildHeadersForUrl(urlObj, { userAgent, authToken } = {}) {
  const headers = {
    "User-Agent": userAgent || DEFAULT_USER_AGENT,
  };

  if (isGitHubApiHost(urlObj.hostname)) {
    headers["Accept"] = "application/vnd.github+json";
    headers["X-GitHub-Api-Version"] = "2022-11-28";
    if (authToken) {
      headers["Authorization"] = `Bearer ${authToken}`;
    }
  }

  return headers;
}

async function followRedirects(url, maxRedirects, actionLabel, once) {
  let currentUrl = url;
  for (
    let redirectCount = 0;
    redirectCount <= maxRedirects;
    redirectCount += 1
  ) {
    const urlObj = new URL(currentUrl);
    // eslint-disable-next-line no-await-in-loop
    const result = await once(urlObj);

    if (result.redirectUrl) {
      currentUrl = result.redirectUrl;
      continue;
    }

    return { ...result, finalUrl: currentUrl };
  }

  throw new Error(`Too many redirects when ${actionLabel} ${url}`);
}

async function requestWithRedirects(
  url,
  {
    timeoutMs = DEFAULT_TIMEOUT_MS,
    maxRedirects = DEFAULT_MAX_REDIRECTS,
    headers = {},
    authToken = null,
    userAgent = DEFAULT_USER_AGENT,
  } = {},
) {
  return await followRedirects(url, maxRedirects, "requesting", async (urlObj) => {
    const requestHeaders = {
      ...buildHeadersForUrl(urlObj, { userAgent, authToken }),
      ...headers,
    };

    const response = await requestOnce(urlObj, requestHeaders, timeoutMs);

    if (
      response.statusCode >= 300 &&
      response.statusCode < 400 &&
      response.headers.location
    ) {
      return {
        redirectUrl: new URL(response.headers.location, urlObj).toString(),
      };
    }

    return response;
  });
}

function requestOnce(urlObj, headers, timeoutMs) {
  return new Promise((resolve, reject) => {
    const requestOptions = {
      hostname: urlObj.hostname,
      path: urlObj.pathname + urlObj.search,
      headers,
    };

    const request = https.get(requestOptions, (response) => {
      const chunks = [];
      let total = 0;
      let settled = false;

      response.on("data", (chunk) => {
        if (settled) return;
        total += chunk.length;
        if (total > MAX_RESPONSE_BODY_BYTES) {
          settled = true;
          response.resume();
          reject(new Error(`Response too large when requesting ${urlObj}`));
          return;
        }
        chunks.push(chunk);
      });

      response.on("end", () => {
        if (settled) return;
        const body = Buffer.concat(chunks);
        resolve({
          statusCode: response.statusCode || 0,
          statusMessage: response.statusMessage || "",
          headers: response.headers || {},
          body,
        });
      });
    });

    request.on("error", reject);
    request.setTimeout(timeoutMs, () => {
      request.destroy();
      reject(new Error("Request timeout"));
    });
  });
}

async function fetchText(url, options = {}) {
  const response = await requestWithRedirects(url, options);
  if (response.statusCode !== 200) {
    throw new HttpError({
      url,
      statusCode: response.statusCode,
      statusMessage: response.statusMessage,
      headers: response.headers,
      bodySnippet: response.body.slice(0, MAX_ERROR_BODY_BYTES).toString("utf8"),
    });
  }
  return response.body.toString("utf8");
}

async function fetchJson(url, options = {}) {
  const text = await fetchText(url, options);
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`Failed to parse JSON: ${error.message}`);
  }
}

async function downloadToFile(
  url,
  filePath,
  {
    timeoutMs = DEFAULT_DOWNLOAD_TIMEOUT_MS,
    maxRedirects = DEFAULT_MAX_REDIRECTS,
    headers = {},
    authToken = null,
    userAgent = DEFAULT_USER_AGENT,
  } = {},
) {
  const result = await followRedirects(
    url,
    maxRedirects,
    "downloading",
    async (urlObj) => {
      const requestHeaders = {
        ...buildHeadersForUrl(urlObj, { userAgent, authToken }),
        ...headers,
      };

      return await downloadOnce(urlObj, filePath, requestHeaders, timeoutMs);
    },
  );

  return { contentType: result.contentType, finalUrl: result.finalUrl };
}

function downloadOnce(urlObj, filePath, headers, timeoutMs) {
  return new Promise((resolve, reject) => {
    const requestOptions = {
      hostname: urlObj.hostname,
      path: urlObj.pathname + urlObj.search,
      headers,
    };

    const request = https.get(requestOptions, (response) => {
      if (
        response.statusCode >= 300 &&
        response.statusCode < 400 &&
        response.headers.location
      ) {
        const redirectUrl = new URL(response.headers.location, urlObj).toString();
        response.resume();
        resolve({ redirectUrl });
        return;
      }

      if (response.statusCode !== 200) {
        const chunks = [];
        let total = 0;
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          if (total < MAX_ERROR_BODY_BYTES) {
            chunks.push(chunk);
            total += chunk.length;
          }
        });
        response.on("end", () => {
          reject(
            new HttpError({
              url: urlObj.toString(),
              statusCode: response.statusCode || 0,
              statusMessage: response.statusMessage || "",
              headers: response.headers || {},
              bodySnippet: chunks.join(""),
            }),
          );
        });
        response.resume();
        return;
      }

      const fileStream = fs.createWriteStream(filePath);
      pipelineAsync(response, fileStream)
        .then(() => {
          resolve({
            contentType: response.headers["content-type"] || "",
            redirectUrl: null,
          });
        })
        .catch((error) => {
          try {
            fs.unlinkSync(filePath);
          } catch {
            // ignore cleanup error
          }
          reject(error);
        });
    });

    request.on("error", (error) => {
      try {
        fs.unlinkSync(filePath);
      } catch {
        // ignore cleanup error
      }
      reject(error);
    });

    request.setTimeout(timeoutMs, () => {
      request.destroy();
      reject(new Error("Download timeout"));
    });
  });
}

module.exports = {
  HttpError,
  fetchJson,
  fetchText,
  downloadToFile,
  isGitHubApiHost,
  buildHeadersForUrl,
};
