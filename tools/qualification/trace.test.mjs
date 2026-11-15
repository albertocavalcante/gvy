// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test, { mock } from "node:test";
import fs from "node:fs";
import { syncBuiltinESMExports } from "node:module";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { once } from "node:events";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { captureTrace } from "./trace.mjs";

test("observer setup closes its first descriptor when the second exclusive open fails", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-open-"));
  const tracePath = join(directory, "trace.log");
  const original = fs.openSync;
  let descriptor;
  mock.method(fs, "openSync", (...args) => {
    const fd = original(...args);
    if (args[0] === tracePath) descriptor = fd;
    return fd;
  });
  syncBuiltinESMExports();
  try {
    await writeFile(`${tracePath}.stdout`, "preexisting");
    await assert.rejects(
      captureTrace([process.execPath, "-e", ""], {
        tracePath,
        resultPath: join(directory, "result.json"),
        deadlineMs: 1000,
      }),
      /EEXIST/,
    );
    assert.throws(() => fs.fstatSync(descriptor), /EBADF/);
  } finally {
    if (descriptor !== undefined) {
      try {
        fs.closeSync(descriptor);
      } catch (error) {
        if (error.code !== "EBADF") throw error;
      }
    }
    mock.restoreAll();
    syncBuiltinESMExports();
    await rm(directory, { recursive: true });
  }
});

test("short descriptor writes preserve the complete observed trace", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-short-"));
  const tracePath = join(directory, "trace.log");
  const resultPath = join(directory, "result.json");
  const original = fs.writeSync;
  mock.method(
    fs,
    "writeSync",
    (
      fd,
      buffer,
      offset = 0,
      length = buffer.length - offset,
      position = null,
    ) => original(fd, buffer, offset, Math.min(3, length), position),
  );
  syncBuiltinESMExports();
  try {
    const captured = await captureTrace(
      [
        process.execPath,
        "-e",
        'process.stderr.write("complete trace\\n");require("fs").writeFileSync(process.argv[1],"{}");',
        resultPath,
      ],
      { tracePath, resultPath, deadlineMs: 2000 },
    );
    assert.equal(await readFile(tracePath, "utf8"), "complete trace\n");
    assert.equal(captured.rawTrace, "complete trace\n");
  } finally {
    mock.restoreAll();
    syncBuiltinESMExports();
    await rm(directory, { recursive: true });
  }
});

test("early observer diagnostics cannot be replaced through the trace pathname", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-replace-"));
  const tracePath = join(directory, "trace.log");
  try {
    const script = `const fs=require('node:fs'); process.stderr.write('original observer failure\\n',()=>{
      fs.renameSync(process.argv[1],process.argv[1]+'.old');
      fs.writeFileSync(process.argv[1],'substituted diagnostics'); process.exit(2);
    });`;
    await assert.rejects(
      captureTrace([process.execPath, "-e", script, tracePath], {
        tracePath,
        resultPath: join(directory, "absent.json"),
        deadlineMs: 2000,
      }),
      (error) =>
        /original observer failure/.test(error.message) &&
        !/substituted/.test(error.message),
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("result marker does not detach observation before a late connection and natural exit", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-late-test-"));
  let connections = 0;
  const server = createServer((socket) => {
    connections++;
    socket.end();
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const resultPath = join(directory, "result.json");
    const releasePath = join(directory, "release");
    const tracePath = join(directory, "trace.log");
    const script = `const fs=require('node:fs'),net=require('node:net');
      fs.writeFileSync(process.argv[1],JSON.stringify({passed:true}));
      const timer=setInterval(()=>{if(!fs.existsSync(process.argv[2]))return;clearInterval(timer);
        const socket=net.createConnection({host:'127.0.0.1',port:Number(process.argv[3])});
        socket.on('connect',()=>{process.stderr.write('42 connect(late-after-result) = 0\\n');socket.end();});
      },10);`;
    let settled = false;
    let markerObserved;
    const observed = new Promise((resolve) => {
      markerObserved = resolve;
    });
    const capture = captureTrace(
      [
        process.execPath,
        "-e",
        script,
        resultPath,
        releasePath,
        String(server.address().port),
      ],
      {
        resultPath,
        tracePath,
        deadlineMs: 5000,
        afterResult: async () => {
          markerObserved();
        },
      },
    ).finally(() => {
      settled = true;
    });
    await Promise.race([
      observed,
      capture.then(() => {
        throw new Error("Capture ended at the result marker");
      }),
    ]);
    assert.equal(settled, false);
    await writeFile(releasePath, "");
    await capture;
    assert.equal(connections, 1);
    assert.match(await readFile(tracePath, "utf8"), /late-after-result/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, { recursive: true });
  }
});

test("a marker from a hanging worker or an early failing observer cannot pass", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-hang-test-"));
  try {
    const script =
      'require("node:fs").writeFileSync(process.argv[1],JSON.stringify({passed:true}));setInterval(()=>{},1000);';
    const resultPath = join(directory, "result.json");
    await assert.rejects(
      captureTrace([process.execPath, "-e", script, resultPath], {
        resultPath,
        tracePath: join(directory, "hang.log"),
        deadlineMs: 250,
      }),
      /deadline/,
    );
    const earlyPath = join(directory, "early.json");
    const early =
      'require("node:fs").writeFileSync(process.argv[1],JSON.stringify({passed:true}));process.exit(2);';
    await assert.rejects(
      captureTrace([process.execPath, "-e", early, earlyPath], {
        resultPath: earlyPath,
        tracePath: join(directory, "early.log"),
        deadlineMs: 2000,
      }),
      /observer.*exit/i,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("capture reads existing stderr descriptor, preserves raw bytes and keeps candidate diagnostics separate", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-test-"));
  try {
    const resultPath = join(directory, "result.json");
    const tracePath = join(directory, "trace.log");
    const candidatePath = join(directory, "candidate.stderr");
    const trace = Buffer.from(
      '42 connect(3, {sa_family=AF_INET, sin_addr=inet_addr("127.0.0.1")}, 16) = 0\n',
    );
    const script =
      'const fs=require("node:fs"); fs.writeFileSync(process.argv[1],"candidate diagnostics"); process.stderr.write(Buffer.from(process.argv[3],"base64")); fs.writeFileSync(process.argv[2],JSON.stringify({passed:true}));';
    const { result, rawTrace } = await captureTrace(
      [
        process.execPath,
        "-e",
        script,
        candidatePath,
        resultPath,
        trace.toString("base64"),
      ],
      { tracePath, resultPath, deadlineMs: 2000 },
    );
    assert.deepEqual(await readFile(tracePath), trace);
    assert.equal(rawTrace, trace.toString());
    assert.equal(
      await readFile(candidatePath, "utf8"),
      "candidate diagnostics",
    );
    assert.deepEqual(result, { passed: true });
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("capture fails closed on byte cap and retains early-exit stderr diagnostics", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-trace-test-"));
  try {
    const options = {
      tracePath: join(directory, "trace.log"),
      resultPath: join(directory, "result.json"),
      deadlineMs: 2000,
      maxBytes: 100,
    };
    await assert.rejects(
      captureTrace(
        [process.execPath, "-e", 'process.stderr.write("x".repeat(101))'],
        options,
      ),
      /byte limit/,
    );
    const second = { ...options, tracePath: join(directory, "early.log") };
    await assert.rejects(
      captureTrace(
        [
          process.execPath,
          "-e",
          'process.stderr.write("strace: intentional early failure\\n"); process.exit(2)',
        ],
        second,
      ),
      /intentional early failure/,
    );
    assert.equal(
      await readFile(second.tracePath, "utf8"),
      "strace: intentional early failure\n",
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

async function cleanupFiniteChild(directory, pidPath, descendant) {
  try {
    // The grandchild publishes its own PID independently of the result marker and self-expires.
    // Observe its disappearance without a PID-based kill that could target a reused identity.
    const deadline = Date.now() + 3500;
    while (!descendant && Date.now() < deadline) {
      try {
        descendant = Number(await readFile(pidPath, "utf8"));
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
      if (!descendant) await new Promise((resolve) => setTimeout(resolve, 10));
    }
    let alive = true;
    while (descendant && alive && Date.now() < deadline) {
      try {
        process.kill(descendant, 0);
      } catch (error) {
        if (error.code !== "ESRCH") throw error;
        alive = false;
      }
      if (alive) await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.ok(descendant, "The child must publish a durable cleanup identity");
    assert.equal(
      alive,
      false,
      "The finite-lived child must be observed drained",
    );
  } finally {
    await rm(directory, { recursive: true });
  }
}

for (const publishMarker of [true, false]) {
  test(`an inherited pipe has bounded cleanup with a ${publishMarker ? "present" : "missing"} result marker`, async () => {
    const directory = await mkdtemp(join(tmpdir(), "gvy-trace-pipe-test-"));
    let descendant;
    let markerObserved = false;
    const pidPath = join(directory, "descendant.pid");
    try {
      const resultPath = join(directory, "result.json");
      const script = `const fs=require('node:fs'),cp=require('node:child_process');
        const childScript="require('node:fs').writeFileSync(process.argv[1],String(process.pid));setTimeout(()=>{},2000);";
        const child=cp.spawn(process.execPath,['-e',childScript,process.argv[2]],{stdio:['ignore','ignore',2]});
        if(process.argv[3]==='true')fs.writeFileSync(process.argv[1],JSON.stringify({pid:child.pid}));child.unref();`;
      await assert.rejects(
        captureTrace(
          [
            process.execPath,
            "-e",
            script,
            resultPath,
            pidPath,
            String(publishMarker),
          ],
          {
            resultPath,
            tracePath: join(directory, "trace.log"),
            deadlineMs: 250,
            afterResult: async (result) => {
              descendant = result.pid;
              markerObserved = true;
            },
          },
        ),
        /deadline|without its result/,
      );
      assert.equal(markerObserved, publishMarker);
    } finally {
      await cleanupFiniteChild(directory, pidPath, descendant);
    }
  });
}
