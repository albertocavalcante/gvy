// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import { ioUringFilter, filteredArgv, verifyCanary } from "./seccomp.mjs";

function evaluate(bytes, arch, nr) {
  let accumulator = 0;
  for (let pc = 0; pc < bytes.length / 8; pc++) {
    const at = pc * 8,
      code = bytes.readUInt16LE(at),
      jt = bytes[at + 2],
      jf = bytes[at + 3],
      k = bytes.readUInt32LE(at + 4);
    if (code === 0x20) accumulator = k === 4 ? arch : nr;
    else if (code === 0x15) pc += accumulator === k ? jt : jf;
    else if (code === 0x45) pc += (accumulator & k) !== 0 ? jt : jf;
    else if (code === 0x06) return k;
    else assert.fail(`Unknown BPF opcode ${code}`);
  }
  assert.fail("Filter fell through");
}

test("fixed filter denies all io_uring calls, refuses unknown/x32 ABI, allows ordinary native calls", () => {
  const bytes = ioUringFilter("x64", "LE");
  assert.equal(bytes.length, 88);
  for (const nr of [425, 426, 427])
    assert.equal(evaluate(bytes, 0xc000003e, nr), 0x50026);
  for (const nr of [0, 1, 41, 42, 59, 424, 428])
    assert.equal(evaluate(bytes, 0xc000003e, nr), 0x7fff0000);
  assert.equal(evaluate(bytes, 0xc000003e, 0x40000000 + 425), 0x80000000);
  assert.equal(evaluate(bytes, 0x40000003, 425), 0x80000000);
  assert.equal(evaluate(bytes, 0xc00000b7, 425), 0x80000000);
  assert.throws(() => ioUringFilter("arm64", "LE"), /ABI/);
  assert.throws(() => ioUringFilter("x64", "BE"), /ABI/);
});

test("launcher argv preserves literal command arguments and requires explicit absolute inputs", () => {
  assert.deepEqual(
    filteredArgv("/helper", "/profile", ["/node", "$(touch nope)", "a b"]),
    ["/helper", "--profile", "/profile", "--", "/node", "$(touch nope)", "a b"],
  );
  assert.throws(
    () => filteredArgv("helper", "/profile", ["/node"]),
    /absolute/,
  );
  assert.throws(
    () => filteredArgv("/helper", "/profile", ["node"]),
    /absolute/,
  );
  assert.throws(
    () => filteredArgv("/helper", "/profile", ["/node", "\0"]),
    /argument/,
  );
});

test("canary requires all three ENOSYS denials plus inherited process restrictions", () => {
  const good = {
    uid: 1000,
    noNewPrivs: 1,
    seccomp: 2,
    forked: true,
    inheritedExtraDescriptors: 0,
    capabilities: {
      effective: "0000000000000000",
      permitted: "0000000000000000",
      inheritable: "0000000000000000",
      ambient: "0000000000000000",
    },
    calls: [
      { name: "io_uring_setup", result: -1, errno: 38 },
      { name: "io_uring_enter", result: -1, errno: 38 },
      { name: "io_uring_register", result: -1, errno: 38 },
    ],
  };
  assert.equal(verifyCanary(good, 1000), true);
  assert.equal(verifyCanary({ ...good, noNewPrivs: 0 }, 1000), false);
  assert.equal(
    verifyCanary({ ...good, calls: good.calls.slice(1) }, 1000),
    false,
  );
  assert.equal(
    verifyCanary(
      { ...good, calls: good.calls.map((x) => ({ ...x, errno: 22 })) },
      1000,
    ),
    false,
  );
  assert.equal(verifyCanary(good, 1001), false);
  assert.equal(
    verifyCanary({ ...good, inheritedExtraDescriptors: 1 }, 1000),
    false,
  );
  assert.equal(verifyCanary({ ...good, forked: false }, 1000), false);
  for (const name of ["effective", "permitted", "inheritable", "ambient"])
    assert.equal(
      verifyCanary(
        {
          ...good,
          capabilities: { ...good.capabilities, [name]: "0000000000000001" },
        },
        1000,
      ),
      false,
    );
  assert.equal(verifyCanary({ ...good, capabilities: undefined }, 1000), false);
});
