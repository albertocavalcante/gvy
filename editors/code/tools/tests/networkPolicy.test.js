// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const { test } = require("node:test");
const {
  parsePolicy,
  normalizeOrigin,
  NetworkPolicyController,
} = require("../../shared/networkPolicy.js");

const fixtures = JSON.parse(
  readFileSync(
    resolve(
      __dirname,
      "../../../../build-tool/src/test/resources/network-policy/conformance.json",
    ),
    "utf8",
  ),
);

for (const fixture of fixtures.configs) {
  test(`policy config: ${fixture.name}`, () => {
    const policy = parsePolicy(fixture.input, 7);
    assert.equal(policy.valid, fixture.valid);
    assert.equal(policy.mode, fixture.mode);
    assert.deepEqual(policy.allowedOrigins, fixture.allowedOrigins);
    assert.equal(policy.revision, 7);
  });
}
for (const fixture of fixtures.origins) {
  test(`policy origin: ${fixture.input}`, () => {
    assert.equal(normalizeOrigin(fixture.input), fixture.normalized);
  });
}
for (const fixture of fixtures.requests) {
  test(`policy request: ${fixture.name}`, () => {
    assert.equal(
      parsePolicy(fixture.policy, 0).authorize(fixture.url)?.code ?? null,
      fixture.code,
    );
  });
}

test("snapshots copy and freeze their authority", () => {
  const input = { mode: "connected", allowedOrigins: ["https://repo.example"] };
  const policy = parsePolicy(input, 0);
  input.allowedOrigins.push("https://evil.example");
  assert.equal(
    policy.authorize("https://evil.example").code,
    "ORIGIN_NOT_ALLOWED",
  );
  assert.ok(Object.isFrozen(policy));
  assert.ok(Object.isFrozen(policy.allowedOrigins));
  assert.throws(() => policy.allowedOrigins.push("https://evil.example"));
});

test("replacement fences results before reentrant cancellation runs", () => {
  const controller = new NetworkPolicyController({
    mode: "connected",
    allowedOrigins: ["https://repo.example"],
  });
  let cancellations = 0;
  const lease = controller.admit("https://repo.example/a", () => {
    cancellations++;
    assert.equal(lease.isCurrent(), false);
    assert.equal(
      controller.admit("https://repo.example/a", () => {}).code,
      "CACHE_ONLY",
    );
  });
  assert.equal(lease.isCurrent(), true);
  controller.replace({});
  assert.equal(cancellations, 1);
  assert.equal(lease.isCurrent(), false);
  lease.close();
  lease.close();
  controller.replace({});
  assert.equal(cancellations, 1);
});

test("a failing cancellation does not strand other operations", () => {
  const controller = new NetworkPolicyController({
    mode: "connected",
    allowedOrigins: ["https://repo.example"],
  });
  controller.admit("https://repo.example/a", () => {
    throw new Error("transport cancellation");
  });
  let cancelled = false;
  controller.admit("https://repo.example/b", () => {
    cancelled = true;
  });
  const failures = controller.replace({});
  assert.equal(cancelled, true);
  assert.equal(failures.length, 1);
  assert.equal(controller.snapshot.revision, 1);
});

test("closed registrations are not cancelled and cannot publish", () => {
  const controller = new NetworkPolicyController({
    mode: "connected",
    allowedOrigins: ["https://repo.example"],
  });
  const lease = controller.admit("https://repo.example", () =>
    assert.fail("closed operation cancelled"),
  );
  lease.close();
  assert.equal(lease.isCurrent(), false);
  controller.replace({});
});

test("rejects unsafe revisions", () => {
  for (const revision of [
    -1,
    NaN,
    Infinity,
    1.5,
    Number.MAX_SAFE_INTEGER + 1,
  ]) {
    assert.throws(() => parsePolicy({}, revision), RangeError);
  }
});
