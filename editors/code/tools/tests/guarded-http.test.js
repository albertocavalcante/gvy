// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const { test } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { setInterval, clearInterval } = require("node:timers");
const { performance } = require("node:perf_hooks");
const { AbortController, queueMicrotask } = globalThis;
const { NetworkPolicyController } = require("../../shared/networkPolicy");
const { getText } = require("../../shared/guardedHttp");

test("unconfigured transport denies before opening a connection", async () => {
  let requests = 0;
  const server = http.createServer((req, res) => {
    requests++;
    res.end("unexpected");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const url = `http://127.0.0.1:${server.address().port}/`;
    await assert.rejects(getText(url, { timeoutMs: 1000, maxBytes: 100 }));
    assert.equal(requests, 0);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("approved origin returns text", async () => {
  const server = http.createServer((req, res) => res.end("hello"));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    const controller = new NetworkPolicyController({
      mode: "connected",
      allowedOrigins: [origin],
    });
    assert.equal(
      await getText(`${origin}/`, {
        controller,
        timeoutMs: 1000,
        maxBytes: 100,
      }),
      "hello",
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { downloadFile, getJson } = require("../../shared/guardedHttp");

async function listening(handler) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { server, origin: `http://127.0.0.1:${server.address().port}` };
}
function policy(...origins) {
  return new NetworkPolicyController({
    mode: "connected",
    allowedOrigins: origins,
  });
}
function options(controller, more = {}) {
  return { controller, timeoutMs: 1000, maxBytes: 1024, ...more };
}
function close(server) {
  server.closeAllConnections();
  return new Promise((resolve) => server.close(resolve));
}

test("redirect to denied origin produces no request there", async () => {
  let received = 0;
  const b = await listening((req, res) => {
    received++;
    res.end("bad");
  });
  const a = await listening((req, res) => {
    res.writeHead(302, { location: b.origin });
    res.end();
  });
  try {
    await assert.rejects(getText(a.origin, options(policy(a.origin))), {
      code: "ORIGIN_NOT_ALLOWED",
    });
    assert.equal(received, 0);
  } finally {
    await close(a.server);
    await close(b.server);
  }
});

test("approved cross-origin redirect strips all initial headers and credentials", async () => {
  let headers;
  const b = await listening((req, res) => {
    headers = req.headers;
    res.end("ok");
  });
  const a = await listening((req, res) => {
    res.writeHead(302, { location: b.origin });
    res.end();
  });
  try {
    const config = options(policy(a.origin, b.origin), {
      headers: { "x-private": "secret" },
      credentials: {
        origin: a.origin,
        headers: { authorization: "Bearer secret", cookie: "session=secret" },
      },
    });
    assert.equal(await getText(a.origin, config), "ok");
    assert.equal(headers.authorization, undefined);
    assert.equal(headers.cookie, undefined);
    assert.equal(headers["x-private"], undefined);
  } finally {
    await close(a.server);
    await close(b.server);
  }
});

test("rejects forbidden authority and transport headers before network", async () => {
  let received = 0;
  const a = await listening((req, res) => {
    received++;
    res.end("bad");
  });
  try {
    const controller = policy(a.origin);
    for (const headers of [
      { host: "evil" },
      { "proxy-authorization": "secret" },
      { "content-length": "3" },
      { authorization: "secret" },
    ]) {
      await assert.rejects(
        getText(a.origin, options(controller, { headers })),
        { code: "INVALID_OPTIONS" },
      );
    }
    for (const url of [
      a.origin.replace("127.0.0.1", "user:secret@127.0.0.1"),
      a.origin + "/#fragment",
      a.origin.replace("127.0.0.1", "%31%32%37.0.0.1"),
    ]) {
      await assert.rejects(getText(url, options(controller)), {
        code: "INVALID_URL",
      });
    }
    assert.equal(received, 0);
  } finally {
    await close(a.server);
  }
});

test("body limit and failure preserve existing destination", async () => {
  const a = await listening((req, res) => res.end("123456789"));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  try {
    await assert.rejects(
      downloadFile(
        a.origin,
        destination,
        options(policy(a.origin), { maxBytes: 4 }),
      ),
      { code: "BODY_TOO_LARGE" },
    );
    assert.equal(fs.readFileSync(destination, "utf8"), "old");
    assert.deepEqual(fs.readdirSync(dir), ["artifact"]);
  } finally {
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("abort and policy replacement stop a blocked response", async () => {
  let arrived;
  const ready = new Promise((resolve) => {
    arrived = resolve;
  });
  const a = await listening((req, res) => {
    res.writeHead(200);
    res.write("x");
    arrived();
  });
  try {
    const controller = policy(a.origin);
    const pending = getText(a.origin, options(controller));
    await ready;
    controller.replace({});
    await assert.rejects(pending, { code: "STALE_POLICY" });
    const external = new AbortController();
    external.abort();
    await assert.rejects(
      getText(a.origin, options(policy(a.origin), { signal: external.signal })),
      { code: "ABORTED" },
    );
  } finally {
    await close(a.server);
  }
});

test("deadline spans slow headers and trickle body", async () => {
  const a = await listening((req, res) => {
    if (req.url === "/headers") setTimeout(() => res.end("late"), 200);
    else {
      res.writeHead(200);
      let n = 0;
      const timer = setInterval(() => {
        if (++n === 10) {
          clearInterval(timer);
          res.end();
        } else res.write("a");
      }, 25);
      res.on("close", () => clearInterval(timer));
    }
  });
  try {
    for (const suffix of ["/headers", "/body"])
      await assert.rejects(
        getText(
          a.origin + suffix,
          options(policy(a.origin), { timeoutMs: 75 }),
        ),
        { code: "TIMEOUT" },
      );
  } finally {
    await close(a.server);
  }
});

test("policy change while receiving a file keeps original destination", async () => {
  let arrived;
  const ready = new Promise((resolve) => {
    arrived = resolve;
  });
  const a = await listening((req, res) => {
    res.write("partial");
    arrived();
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  try {
    const controller = policy(a.origin);
    const pending = downloadFile(a.origin, destination, options(controller));
    await ready;
    controller.replace({});
    await assert.rejects(pending, { code: "STALE_POLICY" });
    assert.equal(fs.readFileSync(destination, "utf8"), "old");
    assert.deepEqual(fs.readdirSync(dir), ["artifact"]);
  } finally {
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("successful download atomically replaces destination and json is decoded", async () => {
  const a = await listening((req, res) => res.end('{"ok":true}'));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  try {
    assert.deepEqual(await getJson(a.origin, options(policy(a.origin))), {
      ok: true,
    });
    await downloadFile(a.origin, destination, options(policy(a.origin)));
    assert.equal(fs.readFileSync(destination, "utf8"), '{"ok":true}');
    assert.deepEqual(fs.readdirSync(dir), ["artifact"]);
  } finally {
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("redirect chain shares one deadline", async () => {
  let requests = 0;
  const a = await listening((req, res) => {
    requests++;
    const n = Number(req.url.slice(1)) || 0;
    setTimeout(() => {
      if (n < 5) {
        res.writeHead(302, { location: `/${n + 1}` });
        res.end();
      } else res.end("late");
    }, 35);
  });
  try {
    await assert.rejects(
      getText(a.origin + "/0", options(policy(a.origin), { timeoutMs: 100 })),
      { code: "TIMEOUT" },
    );
    assert.ok(requests >= 2);
  } finally {
    await close(a.server);
  }
});

test("policy replacement during file staging prevents rename", async () => {
  const a = await listening((req, res) => res.end("new"));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  const original = fs.promises.writeFile;
  const controller = policy(a.origin);
  fs.promises.writeFile = async (...args) => {
    await original(...args);
    controller.replace({});
  };
  try {
    await assert.rejects(
      downloadFile(a.origin, destination, options(controller)),
      { code: "STALE_POLICY" },
    );
    assert.equal(fs.readFileSync(destination, "utf8"), "old");
    assert.deepEqual(fs.readdirSync(dir), ["artifact"]);
  } finally {
    fs.promises.writeFile = original;
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("approved HTTPS to approved HTTP redirect is denied", async () => {
  const https = require("node:https");
  // Static self-signed loopback fixture; this key grants no external authority.
  const key =
    "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC8cg+HNXfREMTh\nHB6FSNML99kmHoFKZ3vMu7+/gVswee/4Agz4WrihzP669NwWVb3G4FJwTbe+CHwL\nKs5Q8TRDZAekGISXP4Xgb31QDxy6uepHUqrCoXJfAxUGSAo7+e+aAUV/77+umR25\nB97RUn1C+BlAYQVMCmpUlxbSAAllNx4yX8ekqONYiXWUkFNKxBUoMvHkgmTVq/IM\nMS32KlGf4DgSPJohxb4qj40ce2gYch0izhV0RFp6/sExCzK3z6h4ouFbNLtsUBED\nQtT39vbmeQ1VLBHsXiuxa6RkcjaMcUdOftFML69d0m/ymKpqX8CUdqa/pvwTm5HT\n+BT6SaMtAgMBAAECggEACIuzoJIQKO71vq+jwq+Wwogu1Me1XUZsGtC41VpsZDpj\nY3/UTr7ueIfvVVMdV/XzcT1i/6zGlFuWJHuAd7M4h4XVwt9JxAIhdBgaOrM0vlMI\nCMv66nCf2ycsoOhLboTbFMTR/gdFTgmri2SL+jofbBYMLxs3PkeKouNnKYbO5kQR\noNlO4u+jBQ27NHAgTWQBfCnHMLGjRas0yR0+T2P5msQuIy8C8LsKtHsmKiLlWzmH\ng635bLVM2d7Ry2WfcLkH4ehY4nmsc3POCPmirU3ix0yNtx3VwzuA8LKtzIcTxMjq\nXqIaWWoaC7Fo0tjkqEacQoKw4McWAuhBtvKy3kqLgQKBgQDuz3Yqzzd/d6R9kON4\nEPYhCJ+WD5zivIykt7bf3hiSi2Gvu/Da1iPt5TNblSZ1DkoMfwL1llbqvI0P+2Nz\ne2vBisKoEnVSsfuvZbK3Gxy2H+4i4zKPsSo4W257un1woU/MiQhpxlDCEimgO5HX\nYyYFUCpbaakkkVWl4tQSiw9fiQKBgQDKAofIkgnbYKhOTzBpDKRcKaGKejVyTwDF\nNJq+BJPmd5IYEaGzmlzzzPPpaDsm4kjn4P62NCyzAKA8N22eTTuJpdhnNevyy4j4\nHcN8BBUp7umUVhrasD2VQbF8zV6GMFODFFLUyV0UEzNGj/HZeVWYBrbQCHibCoTr\nqnj06Wu5hQKBgQCh1T2C7UHyw6VY4mMD7pqc8DcVficXECdPSKBJPTzxsjrU2Ev0\nQMHAaV5QZdbV5IURytC6lNL56CS/+9/9VlpDokEfnMKc87hXuFdP9rOSSDkcs1KE\nRc8SugM5Hbv8I5TsSsDEzXvUyHIk/7lpeOs/FoBdylCxUzvzWq/UkSAt4QKBgDbt\nkTY8X2nDUoBYj/VAdsCIoWAh8r5uNOtqblkUrZKRGKxqvukzI+s242un582e4ewL\nBbdNYIobtXYqLChLH9FBwyHtS4btSQUOMsVZ5tdMGz82Wn70RQp8nmItBXK/WTXf\nJNavGhwHTH33Te+wSlHYIYVXiFO//HyIEfOmNKBxAoGBAKpANo7eTHJzBYOtb++j\nrk1zf/XMdaKEdEqWKjbQF6VWmc9BfS3M25JqGhbXvWxkBAV1wF6cyBuElgjJ7imB\nI6z8bTmAszQUgyrjsyaC1hGxZ2X8uiWhA4S77wmc4uR05wsm0S4eIWOY/z9Q9/kR\ndM5x4lh/uZ9RtC5M62GEiRxW\n-----END PRIVATE KEY-----\n";
  const cert =
    "-----BEGIN CERTIFICATE-----\nMIIDHDCCAgSgAwIBAgIUHqXYvJv3nyi3YF4+jdoYypMpA5owDQYJKoZIhvcNAQEL\nBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MTExNDE1MzI0NloYDzIxMjYx\nMDIxMTUzMjQ2WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB\nAQUAA4IBDwAwggEKAoIBAQC8cg+HNXfREMThHB6FSNML99kmHoFKZ3vMu7+/gVsw\nee/4Agz4WrihzP669NwWVb3G4FJwTbe+CHwLKs5Q8TRDZAekGISXP4Xgb31QDxy6\nuepHUqrCoXJfAxUGSAo7+e+aAUV/77+umR25B97RUn1C+BlAYQVMCmpUlxbSAAll\nNx4yX8ekqONYiXWUkFNKxBUoMvHkgmTVq/IMMS32KlGf4DgSPJohxb4qj40ce2gY\nch0izhV0RFp6/sExCzK3z6h4ouFbNLtsUBEDQtT39vbmeQ1VLBHsXiuxa6RkcjaM\ncUdOftFML69d0m/ymKpqX8CUdqa/pvwTm5HT+BT6SaMtAgMBAAGjZDBiMB0GA1Ud\nDgQWBBSPg2oXex+aQ0d/Ho6GLyBLTVBBCjAfBgNVHSMEGDAWgBSPg2oXex+aQ0d/\nHo6GLyBLTVBBCjAPBgNVHRMBAf8EBTADAQH/MA8GA1UdEQQIMAaHBH8AAAEwDQYJ\nKoZIhvcNAQELBQADggEBACpipcV2j17NGDp2U8joyUiOhSFeb8DX4X9nI5Hw6yml\nOhIyhCIZGfhdsm6ySsLzoO6gEsSYVE87GpzqL/or1JX6AHMpAi+W9xxNv7wkXxsg\ngP9zY3JhzLHOz3ztaG419RYvWLxx2EtxOdYC+lNQyd+EwPNYFvZ5rYhQnMjUBfd6\n69aABC3lAX7BnqDBhGJEv32z0XORXCDOF/By0oIM8eYPA/z5KkCFU6jE104WJdev\ncbaU+anrdJnAYelKigpoRuFsn8YjaEW5e+GQImX/c9rHv1MSgj4hc9d2NKoxEl9E\n3fgSNYIPe1wniQPKjP9XGpC3M3AaCiLPH8bOarBgrhs=\n-----END CERTIFICATE-----\n";
  let destinationRequests = 0;
  const b = await listening((req, res) => {
    destinationRequests++;
    res.end("bad");
  });
  const server = https.createServer({ key, cert }, (req, res) => {
    res.writeHead(302, { location: b.origin });
    res.end();
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const a = `https://127.0.0.1:${server.address().port}`;
  try {
    await assert.rejects(
      getText(a, options(policy(a, b.origin), { ca: cert })),
      { code: "TLS_DOWNGRADE" },
    );
    assert.equal(destinationRequests, 0);
  } finally {
    await close(server);
    await close(b.server);
  }
});

test("caller abort reaches an active response", async () => {
  let arrived;
  const ready = new Promise((resolve) => {
    arrived = resolve;
  });
  const a = await listening((req, res) => {
    res.write("x");
    arrived();
  });
  try {
    const abort = new AbortController();
    const pending = getText(
      a.origin,
      options(policy(a.origin), { signal: abort.signal }),
    );
    await ready;
    abort.abort();
    await assert.rejects(pending, { code: "ABORTED" });
  } finally {
    await close(a.server);
  }
});

test("invalid sizes and redirect limit deny before further acquisition", async () => {
  let received = 0;
  const a = await listening((req, res) => {
    received++;
    res.writeHead(302, { location: "/loop" });
    res.end();
  });
  try {
    for (const maxBytes of [0, -1, 1.5, 64 * 1024 * 1024 + 1])
      await assert.rejects(
        getText(a.origin, options(policy(a.origin), { maxBytes })),
        { code: "INVALID_OPTIONS" },
      );
    assert.equal(received, 0);
    await assert.rejects(
      getText(a.origin, options(policy(a.origin), { maxRedirects: 0 })),
      { code: "TOO_MANY_REDIRECTS" },
    );
    assert.equal(received, 1);
  } finally {
    await close(a.server);
  }
});

test("invalid JSON reports a structured failure without body content", async () => {
  const a = await listening((req, res) => res.end("private invalid contents"));
  try {
    await assert.rejects(
      getJson(a.origin, options(policy(a.origin))),
      (error) =>
        error.code === "INVALID_JSON" && !error.message.includes("private"),
    );
  } finally {
    await close(a.server);
  }
});

test("unexpected body termination fails without replacing the destination", async () => {
  const a = await listening((req, res) => {
    res.writeHead(200, { "content-length": "20" });
    res.write("short");
    res.socket.destroy();
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const dest = path.join(dir, "artifact");
  fs.writeFileSync(dest, "old");
  try {
    await assert.rejects(
      downloadFile(a.origin, dest, options(policy(a.origin))),
    );
    assert.equal(fs.readFileSync(dest, "utf8"), "old");
    assert.deepEqual(fs.readdirSync(dir), ["artifact"]);
  } finally {
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("malformed raw redirect authority is rejected without following it", async () => {
  let requests = 0;
  const a = await listening((req, res) => {
    requests++;
    res.writeHead(302, {
      location: `http://name:secret@127.0.0.1:${a.server.address().port}/next`,
    });
    res.end();
  });
  try {
    await assert.rejects(
      getText(a.origin, options(policy(a.origin))),
      (error) =>
        error.code === "INVALID_URL" && !error.message.includes("secret"),
    );
    assert.equal(requests, 1);
  } finally {
    await close(a.server);
  }
});

test("completed requests unregister cancellation before policy replacement", async () => {
  const a = await listening((req, res) => res.end("ok"));
  let cancellations = 0;
  const controller = policy(a.origin);
  const original = controller.admit.bind(controller);
  controller.admit = (url, cancel) =>
    original(url, () => {
      cancellations++;
      cancel();
    });
  try {
    assert.equal(await getText(a.origin, options(controller)), "ok");
    controller.replace({});
    assert.equal(cancellations, 0);
  } finally {
    await close(a.server);
  }
});

test("credentials are sent only to their explicitly approved origin", async () => {
  let received;
  const a = await listening((req, res) => {
    received = req.headers.authorization;
    res.end("ok");
  });
  try {
    assert.equal(
      await getText(
        a.origin,
        options(policy(a.origin), {
          credentials: {
            origin: a.origin,
            headers: { authorization: "Bearer token" },
          },
        }),
      ),
      "ok",
    );
    assert.equal(received, "Bearer token");
  } finally {
    await close(a.server);
  }
});

test("cleanup failure after rename reports the committed result as success", async () => {
  const a = await listening((req, res) => res.end("new"));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  const originalRemove = fs.promises.rm;
  fs.promises.rm = async () => {
    throw new Error("cleanup failed");
  };
  try {
    const result = await downloadFile(
      a.origin,
      destination,
      options(policy(a.origin)),
    );
    assert.equal(fs.readFileSync(destination, "utf8"), "new");
    assert.equal(result.committed, true);
    assert.equal(path.dirname(result.cleanupError.path), dir);
    assert.deepEqual(fs.readdirSync(result.cleanupError.path), []);
  } finally {
    fs.promises.rm = originalRemove;
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("unexpected protocol upgrade settles and unregisters the request", async () => {
  const sockets = new Set();
  const a = await listening((req, res) => {
    res.socket.write(
      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
    );
  });
  a.server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  let cancellations = 0;
  const controller = policy(a.origin);
  const original = controller.admit.bind(controller);
  controller.admit = (url, cancel) =>
    original(url, () => {
      cancellations++;
      cancel();
    });
  try {
    const result = await Promise.race([
      getText(a.origin, options(controller, { timeoutMs: 30 })).then(
        () => "unexpected success",
        (error) => error.code,
      ),
      new Promise((resolve) => setTimeout(() => resolve("hung"), 300)),
    ]);
    assert.notEqual(result, "hung");
    assert.notEqual(result, "unexpected success");
    controller.replace({});
    assert.equal(cancellations, 0);
  } finally {
    for (const socket of sockets) socket.destroy();
    await close(a.server);
  }
});

test("policy change queued at text completion denies result", async () => {
  const a = await listening((req, res) => res.end("old"));
  const controller = policy(a.origin);
  const admit = controller.admit.bind(controller);
  let checks = 0;
  controller.admit = (url, cancel) => {
    const admission = admit(url, cancel);
    if ("code" in admission) return admission;
    return {
      ...admission,
      isCurrent() {
        const current = admission.isCurrent();
        if (++checks === 2) queueMicrotask(() => controller.replace({}));
        return current;
      },
    };
  };
  try {
    await assert.rejects(getText(a.origin, options(controller)), {
      code: "STALE_POLICY",
    });
  } finally {
    await close(a.server);
  }
});

test("cleanup failure after abort exposes private path and cancellation cause", async () => {
  const a = await listening((req, res) => res.end("new"));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-http-test-"));
  const destination = path.join(dir, "artifact");
  fs.writeFileSync(destination, "old");
  const originalWrite = fs.promises.writeFile;
  const originalRemove = fs.promises.rm;
  const abort = new AbortController();
  fs.promises.writeFile = async (...args) => {
    await originalWrite(...args);
    abort.abort();
  };
  fs.promises.rm = async () => {
    throw new Error("injected cleanup failure");
  };
  try {
    await assert.rejects(
      downloadFile(
        a.origin,
        destination,
        options(policy(a.origin), { signal: abort.signal }),
      ),
      (error) =>
        error.code === "CLEANUP_FAILED" &&
        path.dirname(error.cleanupPath) === dir &&
        error.cause?.code === "ABORTED",
    );
    assert.equal(fs.readFileSync(destination, "utf8"), "old");
  } finally {
    fs.promises.writeFile = originalWrite;
    fs.promises.rm = originalRemove;
    await close(a.server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("abort waits for local request and response close before unregistering", async () => {
  let arrived;
  const ready = new Promise((resolve) => {
    arrived = resolve;
  });
  const a = await listening((req, res) => {
    res.write("x");
    arrived();
  });
  const events = [];
  let sawResponse;
  const responseSeen = new Promise((resolve) => {
    sawResponse = resolve;
  });
  const originalGet = http.get;
  http.get = (...args) => {
    const req = originalGet(...args);
    req.on("close", () => events.push("request-close"));
    req.on("response", (res) => {
      res.on("close", () => events.push("response-close"));
      sawResponse();
    });
    return req;
  };
  const controller = policy(a.origin);
  const originalAdmit = controller.admit.bind(controller);
  controller.admit = (url, cancel) => {
    const admission = originalAdmit(url, cancel);
    if ("code" in admission) return admission;
    return {
      ...admission,
      close() {
        events.push("admission-close");
        admission.close();
      },
    };
  };
  try {
    const abort = new AbortController();
    const pending = getText(
      a.origin,
      options(controller, { signal: abort.signal }),
    );
    await ready;
    await responseSeen;
    abort.abort();
    await assert.rejects(pending, { code: "ABORTED" });
    assert.ok(
      events.indexOf("request-close") < events.indexOf("admission-close"),
      events.join(","),
    );
    assert.ok(
      events.indexOf("response-close") < events.indexOf("admission-close"),
      events.join(","),
    );
    assert.ok(
      events.includes("request-close") && events.includes("response-close"),
    );
  } finally {
    http.get = originalGet;
    await close(a.server);
  }
});

test("JSON decode cannot publish after its monotonic deadline", async () => {
  const a = await listening((req, res) => res.end('{"ok":true}'));
  const parse = JSON.parse;
  JSON.parse = (...args) => {
    const until = performance.now() + 140;
    while (performance.now() < until) {
      /* simulate synchronous decoder work */
    }
    return parse(...args);
  };
  try {
    await assert.rejects(
      getJson(a.origin, options(policy(a.origin), { timeoutMs: 100 })),
      { code: "TIMEOUT" },
    );
  } finally {
    JSON.parse = parse;
    await close(a.server);
  }
});
