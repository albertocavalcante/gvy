// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyTrace,
  validateManifest,
  qualificationReceipt,
  validateExecutionHost,
} from "./core.mjs";

test("qualification refuses root, Windows binaries and WSL interoperability even when a mechanism probe is allowed", () => {
  const host = {
    platform: "linux",
    uid: 1000,
    euid: 1000,
    nodeMajor: 24,
    elf: true,
    kernel: "6.1-linux",
    interop: false,
  };
  assert.equal(validateExecutionHost(host, false).wsl, false);
  for (const patch of [
    { uid: 0 },
    { elf: false },
    { nodeMajor: 17 },
    { interop: true },
    { kernel: "6.6-microsoft-standard-WSL2" },
  ]) {
    assert.throws(() => validateExecutionHost({ ...host, ...patch }, false));
  }
  assert.equal(
    validateExecutionHost(
      { ...host, kernel: "6.6-microsoft-standard-WSL2", interop: true },
      true,
    ).wsl,
    true,
  );
});

const manifest = () => ({
  schemaVersion: 1,
  exclusiveGradleLane: true,
  deadlineSeconds: 60,
  artifacts: {
    serverJar: { path: "/candidate/server.jar", sha256: "a".repeat(64) },
    extensionVsix: { path: "/candidate/editor.vsix", sha256: "b".repeat(64) },
  },
  gradle: { installation: "/tools/gradle", version: "8.5" },
  javaHome: "/tools/jdk",
  scenarios: [
    {
      id: "startup",
      kind: "lsp",
      cwd: "/fixture",
      initializationOptions: {},
      steps: [],
    },
  ],
});

test("manifest requires exact artifact identities, absolute paths, nonempty real scenarios and an exclusive lane", () => {
  assert.equal(validateManifest(manifest()).scenarios.length, 1);
  for (const mutate of [
    (m) => {
      m.artifacts.serverJar.sha256 = "latest";
    },
    (m) => {
      m.javaHome = "relative";
    },
    (m) => {
      m.scenarios = [];
    },
    (m) => {
      m.exclusiveGradleLane = false;
    },
    (m) => {
      m.deadlineSeconds = 0;
    },
    (m) => {
      m.scenarios[0].kind = "mock";
    },
    (m) => {
      m.scenarios[0] = {
        id: "cli",
        kind: "command",
        cwd: "/fixture",
        argv: "sh -c injected",
      };
    },
    (m) => {
      m.scenarios[0] = {
        id: "../escape",
        kind: "command",
        cwd: "/fixture",
        argv: ["/bin/true"],
      };
    },
  ]) {
    const value = manifest();
    mutate(value);
    assert.throws(() => validateManifest(value));
  }
});

test("socket creation is distinct from attempts; all IPv4 loopback and IPv6 loopback are local", () => {
  const result = classifyTrace(`10 socket(AF_INET, SOCK_STREAM, IPPROTO_TCP) = 3
10 connect(3, {sa_family=AF_INET, sin_port=htons(42), sin_addr=inet_addr("127.2.3.4")}, 16) = 0
11 connect(4, {sa_family=AF_INET6, sin6_port=htons(42), inet_pton(AF_INET6, "::1", &sin6_addr)}, 28) = 0
11 connect(5, {sa_family=AF_INET6, inet_pton(AF_INET6, "::ffff:127.0.0.1", &sin6_addr)}, 28) = 0`);
  assert.equal(result.socketCreations, 1);
  assert.equal(result.loopbackAttempts, 3);
  assert.equal(result.externalAttempts, 0);
  assert.equal(result.unclassified.length, 0);
});

test("unprefixed main-thread syscalls are classified without hiding warnings or io_uring", () => {
  const trace = classifyTrace(
    'getsockname(1<UNIX-STREAM:[1->2]>, {sa_family=AF_UNIX}, [128 => 2]) = 0\nconnect(3, {sa_family=AF_INET, sin_addr=inet_addr("198.51.100.1")}, 16) = -1 ENETUNREACH (Network is unreachable)\nio_uring_setup(256, {}) = 11',
  );
  assert.equal(trace.externalAttempts, 1);
  assert.equal(trace.blockedExternalAttempts, 1);
  assert.equal(trace.unclassified.length, 1);
  assert.match(trace.unclassified[0], /io_uring_setup/);
});

test("counts denied TCP, UDP sendto/sendmsg/sendmmsg attempts independently of prevention", () => {
  const result =
    classifyTrace(`10 connect(3, {sa_family=AF_INET, sin_addr=inet_addr("198.51.100.1")}, 16) = -1 ENETUNREACH (Network is unreachable)
11 sendto(4, ""..., 8, 0, {sa_family=AF_INET, sin_addr=inet_addr("203.0.113.1")}, 16) = -1 ENETUNREACH (Network is unreachable)
12 sendmsg(5, {msg_name={sa_family=AF_INET6, inet_pton(AF_INET6, "2001:db8::1", &sin6_addr)}}, 0) = -1 ENETUNREACH (Network is unreachable)
13 sendmmsg(6, [{msg_hdr={msg_name={sa_family=AF_INET, sin_addr=inet_addr("192.0.2.1")}}}], 1, 0) = -1 ENETUNREACH (Network is unreachable)`);
  assert.equal(result.externalAttempts, 4);
  assert.equal(result.blockedExternalAttempts, 4);
});

test("unfinished/resumed syscalls are reassembled by pid and incomplete or ambiguous traffic fails closed", () => {
  const result =
    classifyTrace(`10 connect(3, {sa_family=AF_INET, sin_addr=inet_addr("198.51.100.1")}, 16 <unfinished ...>
11 socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP) = 4
10 <... connect resumed>) = -1 ENETUNREACH (Network is unreachable)
12 sendmsg(4, {msg_name=NULL}, 0) = 2
13 connect(5, {sa_family=AF_UNIX, sun_path="/run/proxy"}, 40) = 0
14 io_uring_setup(8, {}) = -1 EPERM (Operation not permitted)
15 connect(3, <unfinished ...>`);
  assert.equal(result.externalAttempts, 1);
  assert.equal(result.unixAttempts, 1);
  assert.equal(result.unclassified.length, 3);
});

test("connected UDP endpoint annotations are classified; malformed trace never reports clean", () => {
  const result = classifyTrace(
    '4 sendto(5<UDP:[127.0.0.1:555->192.0.2.2:42]>, ""..., 3, 0, NULL, 0) = 3\nstrace: Process 4 detached',
  );
  assert.equal(result.externalAttempts, 1);
  assert.equal(result.blockedExternalAttempts, 0);
  assert.equal(result.unclassified.length, 1);
});

test("detaching an idle receiver does not invent outbound uncertainty; raw sockets do", () => {
  assert.equal(
    classifyTrace(
      "42 recvfrom(3, <unfinished ...>\n43 accept(4, <unfinished ...>",
    ).unclassified.length,
    0,
  );
  assert.equal(
    classifyTrace("42 socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL)) = 3")
      .unclassified.length,
    1,
  );
});

test("receipt cannot convert prevention, mocked units or a passing generic command into complete qualification", () => {
  const receipt = qualificationReceipt({
    namespace: { verified: true },
    cleanup: { verified: true },
    observer: { naturalExit: true, exitCode: 0, drained: true },
    processTeardown: { verified: true, workerExitedNaturally: true },
    ioUringEnforcement: { verified: true },
    lifetimeControl: { verified: true },
    negativeControl: { verified: true },
    trace: classifyTrace(""),
    scenarios: [{ id: "cli", kind: "command", passed: true }],
  });
  assert.equal(receipt.completeAcceptanceMatrixQualified, false);
  assert.equal(receipt.networkObservationPassed, true);
  assert.equal(
    qualificationReceipt({ ...receipt, lifetimeControl: undefined })
      .networkObservationPassed,
    false,
  );
  assert.equal(receipt.scenarios[0].kind, "command");
  assert.equal(
    qualificationReceipt({ ...receipt, observer: undefined })
      .networkObservationPassed,
    false,
  );
  assert.equal(
    qualificationReceipt({ ...receipt, processTeardown: undefined })
      .networkObservationPassed,
    false,
  );
  assert.equal(
    qualificationReceipt({ ...receipt, ioUringEnforcement: undefined })
      .networkObservationPassed,
    false,
  );
  const uncertain = qualificationReceipt({
    ...receipt,
    trace: classifyTrace("1 sendmsg(4, {msg_name=NULL}, 0) = 1"),
  });
  assert.equal(uncertain.networkObservationPassed, false);
});

test("io_uring is accepted only as explicit ENOSYS denial and success/other errors remain unqualified", () => {
  const denied = classifyTrace(
    "42 io_uring_setup(256, {}) = -1 ENOSYS (Function not implemented)\n42 io_uring_enter(-1, 0, 0, 0, NULL, 0) = -1 ENOSYS (Function not implemented)\n42 io_uring_register(-1, 0, NULL, 0) = -1 ENOSYS (Function not implemented)",
  );
  assert.deepEqual(
    denied.deniedIoUring.map((value) => value.syscall),
    ["io_uring_setup", "io_uring_enter", "io_uring_register"],
  );
  assert.equal(denied.unclassified.length, 0);
  for (const result of [
    "3<anon_inode:[io_uring]>",
    "-1 EPERM (Operation not permitted)",
    "-1 EINVAL (Invalid argument)",
    "?",
  ]) {
    assert.equal(
      classifyTrace(`42 io_uring_setup(256, {}) = ${result}`).unclassified
        .length,
      1,
    );
  }
});
