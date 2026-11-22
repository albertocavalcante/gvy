// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import { validateLifetimeInvocation } from "./lifetime-core.mjs";

test("lifetime control preserves any non-root host uid and rejects unsupported execution before effects", () => {
  const valid = {
    role: "host",
    directory: "/private/result",
    helper: "/tools/helper",
    profile: "/private/profile",
    platform: "linux",
    uid: 1001,
    euid: 1001,
  };
  assert.doesNotThrow(() => validateLifetimeInvocation(valid));
  for (const role of ["init", "worker", "descendant"])
    assert.doesNotThrow(() => validateLifetimeInvocation({ ...valid, role }));
  for (const update of [
    { uid: 0, euid: 0 },
    { euid: 1000 },
    { platform: "darwin" },
    { role: "candidate" },
    { directory: "relative" },
    { helper: "/bad\0helper" },
    { profile: undefined },
  ])
    assert.throws(() => validateLifetimeInvocation({ ...valid, ...update }));
});
