// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { isDeepStrictEqual } from "node:util";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  linkSync,
  readFileSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";
import {
  createWindowsPrivateRoot,
  inspectWindowsPrivateRoot,
  inspectWindowsPrivateChild,
  verifyWindowsPrivateRoot,
} from "./windows-private-root.mjs";

const options = { helperPath: process.env.GVY_PRIVATE_ROOT_HELPER };
const denied = (error) =>
  error.message === "Cannot establish a private Windows provisioning root" &&
  error.cause === undefined;

test("invalid private paths fail without exposing input", () => {
  for (const path of [
    "",
    "relative",
    "C:relative",
    "\\root-relative",
    "/root-relative",
    "C:\\PRIVATE\n",
    "C:\\PRIVATE\u0000",
    "C:\\bad\ud800",
  ])
    assert.throws(() => createWindowsPrivateRoot(path, options), denied);
});

if (process.platform === "win32") {
  let root;
  let failedHelper;
  let waitingHelper;
  before(() => {
    root = mkdtempSync(join(tmpdir(), "gvy-private-root-controls-"));
    const source = join(root, "fault.c");
    writeFileSync(
      source,
      '#include <windows.h>\n#include <stdio.h>\nint main(void) { fputs("PRIVATE-STDERR-CANARY", stderr); fputs("PRIVATE-PARTIAL-STDOUT", stdout); fflush(stdout); fflush(stderr);\n#ifdef WAIT\nSleep(15000);\n#endif\nreturn 9; }\n',
    );
    failedHelper = join(root, "failure.exe");
    waitingHelper = join(root, "waiting.exe");
    for (const [executable, extra] of [
      [failedHelper, []],
      [waitingHelper, ["-DWAIT"]],
    ]) {
      const result = spawnSync(
        process.env.GVY_TEST_CC,
        [
          "-std=c11",
          "-static",
          "-Wall",
          "-Wextra",
          "-Werror",
          ...extra,
          source,
          "-o",
          executable,
        ],
        { encoding: "utf8", timeout: 30000 },
      );
      assert.ifError(result.error);
      assert.equal(result.status, 0, result.stderr);
    }
  });
  after(() => rmSync(root, { recursive: true, force: true }));
  const invoke = (mode, path) =>
    spawnSync(options.helperPath, [mode], {
      input: Buffer.from(path),
      encoding: "utf8",
      timeout: 10000,
    });
  const ps = (source, env) => {
    const file = join(root, "change-acl.ps1");
    writeFileSync(file, `$ErrorActionPreference='Stop'\n${source}\n`);
    const result = spawnSync(
      process.env.GVY_TEST_PWSH,
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-File", file],
      { env: { ...process.env, ...env }, encoding: "utf8", timeout: 10000 },
    );
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
    return result.stdout;
  };

  test("fresh Unicode root has an exact owner-only protected inheritable DACL and stable identity", () => {
    const path = join(root, "private 世界 λ 🦜");
    const identity = createWindowsPrivateRoot(path, options);
    assert.equal(Object.isFrozen(identity), true);
    assert.ok(
      isDeepStrictEqual(inspectWindowsPrivateRoot(path, options), identity),
      "Root identity differs",
    );
    assert.ok(
      identity.ownerSid.startsWith("S-1-"),
      "Root SID has invalid shape",
    );
    assert.ok(
      isDeepStrictEqual(
        verifyWindowsPrivateRoot(path, identity, options),
        identity,
      ),
      "Verified root identity differs",
    );
    writeFileSync(join(path, "usable.txt"), "compiler fixture");
    assert.equal(
      readFileSync(join(path, "usable.txt"), "utf8"),
      "compiler fixture",
    );
    ps(
      "$acl=Get-Acl -LiteralPath $env:TEST_PATH; [Console]::WriteLine($acl.Sddl)",
      { TEST_PATH: path },
    );
  });

  test("exclusive creation rejects existing directories, files, missing parents and links", () => {
    const existing = join(root, "existing");
    mkdirSync(existing);
    const alreadyPrivate = join(root, "already-private");
    createWindowsPrivateRoot(alreadyPrivate, options);
    const file = join(root, "file");
    writeFileSync(file, "PRIVATE");
    const link = join(root, "junction");
    symlinkSync(existing, link, "junction");
    for (const path of [
      existing,
      alreadyPrivate,
      file,
      link,
      join(root, "missing", "child"),
    ]) {
      assert.throws(() => createWindowsPrivateRoot(path, options), denied);
      const result = invoke("create", path);
      assert.notEqual(result.status, 0);
      assert.equal(result.stdout, "");
      assert.doesNotMatch(result.stderr, /PRIVATE|existing|missing/);
    }
  });

  test("ordinary inherited directories and junctions cannot be verified as private roots", () => {
    const path = join(root, "ordinary");
    mkdirSync(path);
    assert.throws(() => verifyWindowsPrivateRoot(path, {}, options), denied);
    const defaultDirectory = invoke("verify", path);
    assert.notEqual(defaultDirectory.status, 0);
    assert.equal(defaultDirectory.stdout, "");
    const file = join(root, "regular-file-with-private-ACL");
    writeFileSync(file, "private fixture");
    const rejectedFile = invoke("verify", file);
    assert.notEqual(rejectedFile.status, 0);
    assert.equal(rejectedFile.stdout, "");

    const privatePath = join(root, "junction-target");
    const identity = createWindowsPrivateRoot(privatePath, options);
    const link = join(root, "private-junction");
    symlinkSync(privatePath, link, "junction");
    assert.throws(
      () => verifyWindowsPrivateRoot(link, identity, options),
      denied,
    );
  });

  test("broadened, unprotected and foreign-owner ACLs are rejected", () => {
    for (const mode of ["broaden", "inherit", "owner"]) {
      const path = join(root, mode);
      const identity = createWindowsPrivateRoot(path, options);
      const source =
        `$acl=Get-Acl -LiteralPath $env:TEST_PATH\n` +
        {
          broaden: `$sid=[System.Security.Principal.SecurityIdentifier]::new('S-1-1-0'); $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'ReadAndExecute','ContainerInherit,ObjectInherit','None','Allow'); $acl.AddAccessRule($rule)`,
          inherit: `$acl.SetAccessRuleProtection($false,$true)`,
          owner: `$acl.SetOwner([System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))`,
        }[mode] +
        `\nSet-Acl -LiteralPath $env:TEST_PATH -AclObject $acl`;
      ps(source, { TEST_PATH: path });
      assert.throws(
        () => verifyWindowsPrivateRoot(path, identity, options),
        denied,
      );
    }
  });

  test("same pathname with a replacement private directory fails the original identity", () => {
    const path = join(root, "replace");
    const identity = createWindowsPrivateRoot(path, options);
    renameSync(path, path + "-original");
    const replacement = createWindowsPrivateRoot(path, options);
    assert.ok(
      !isDeepStrictEqual(replacement, identity),
      "Replacement identity unexpectedly matches",
    );
    assert.throws(
      () => verifyWindowsPrivateRoot(path, identity, options),
      denied,
    );
    assert.ok(
      isDeepStrictEqual(
        verifyWindowsPrivateRoot(path, replacement, options),
        replacement,
      ),
      "Replacement verification differs",
    );
  });

  test(
    "helper failures and the ten-second timeout discard stderr and partial stdout",
    { timeout: 15000 },
    () => {
      for (const helperPath of [failedHelper, waitingHelper])
        assert.throws(
          () =>
            createWindowsPrivateRoot(join(root, "PRIVATE-PATH"), {
              helperPath,
            }),
          denied,
        );
    },
  );

  test("missing helper setup and helper failure preserve privacy", () => {
    for (const helperPath of [
      undefined,
      "relative.exe",
      join(root, "PRIVATE-missing.exe"),
      root,
    ])
      assert.throws(
        () => createWindowsPrivateRoot(join(root, "safe"), { helperPath }),
        denied,
      );
    const result = invoke("wrong", join(root, "PRIVATE"));
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, "");
    assert.doesNotMatch(result.stderr, /PRIVATE/);
  });
  test("ordinary inherited child directories and files preserve SID-only access and full identity", () => {
    const path = join(root, "children");
    const rootIdentity = createWindowsPrivateRoot(path, options);
    const directory = join(path, "sdk");
    mkdirSync(directory);
    const file = join(directory, "compiler.bat");
    writeFileSync(file, "compiler fixture");
    const inspectChild = (path, type) =>
      inspectWindowsPrivateChild(path, { ...options, type, rootIdentity });
    for (const [childPath, type] of [
      [directory, "directory"],
      [file, "file"],
      [path, "directory"],
    ]) {
      const value = inspectChild(childPath, type);
      assert.equal(Object.isFrozen(value), true);
      assert.equal(value.type, type);
      assert.ok(
        value.principalSid === rootIdentity.ownerSid,
        "Child principal differs from root",
      );
      assert.ok(["token-user", "token-owner"].includes(value.ownerSource));
      assert.ok(
        /^[0-9a-f]{32}$/.test(value.fileId),
        "Invalid full file ID encoding",
      );
      assert.ok(
        /^[0-9a-f]{16}$/.test(value.volumeSerial),
        "Invalid volume encoding",
      );
      assert.match(value.sizeBytes, /^[0-9a-f]{16}$/);
      assert.equal(value.linkCount, 1);
      if (type === "file") assert.equal(BigInt("0x" + value.sizeBytes), 16n);
    }
  });

  test("child inspection rejects foreign/group/Everyone ACLs and reduced access", () => {
    const path = join(root, "child-acl");
    const rootIdentity = createWindowsPrivateRoot(path, options);
    for (const type of ["directory", "file"])
      for (const mode of ["everyone", "group", "foreign", "reduced"]) {
        const child = join(path, type + "-" + mode);
        if (type === "directory") mkdirSync(child);
        else writeFileSync(child, "fixture");
        const principal = {
          everyone: "S-1-1-0",
          group: "S-1-5-32-544",
          foreign: "S-1-5-21-0-0-0-998",
        }[mode];
        if (mode === "reduced") {
          ps(
            "$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $acl=Get-Acl -LiteralPath $env:TEST_PATH; $flags=if($env:TEST_TYPE -eq 'directory'){'OICI'}else{''}; $acl.SetSecurityDescriptorSddlForm('O:'+$sid+'D:P(A;'+$flags+';FR;;;'+$sid+')'); Set-Acl -LiteralPath $env:TEST_PATH -AclObject $acl",
            { TEST_PATH: child, TEST_TYPE: type },
          );
        } else {
          ps(
            "$acl=Get-Acl -LiteralPath $env:TEST_PATH; $sid=[System.Security.Principal.SecurityIdentifier]::new($env:TEST_SID); $flags=if($env:TEST_TYPE -eq 'directory'){'ContainerInherit,ObjectInherit'}else{'None'}; $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'ReadAndExecute',$flags,'None','Allow'); $acl.AddAccessRule($rule); Set-Acl -LiteralPath $env:TEST_PATH -AclObject $acl",
            { TEST_PATH: child, TEST_SID: principal, TEST_TYPE: type },
          );
        }
        assert.throws(
          () =>
            inspectWindowsPrivateChild(child, {
              ...options,
              type,
              rootIdentity,
            }),
          denied,
        );
      }
  });

  test("child inspection rejects wrong types, reparse points, hardlinks and changed principal binding", () => {
    const path = join(root, "child-types");
    const rootIdentity = createWindowsPrivateRoot(path, options);
    const directory = join(path, "sdk");
    mkdirSync(directory);
    const file = join(directory, "compiler.bat");
    writeFileSync(file, "compiler fixture");
    const inspectChild = (path, type, identity = rootIdentity) =>
      inspectWindowsPrivateChild(path, {
        ...options,
        type,
        rootIdentity: identity,
      });
    assert.throws(() => inspectChild(file, "directory"), denied);
    assert.throws(() => inspectChild(directory, "file"), denied);
    const junction = join(path, "junction");
    symlinkSync(directory, junction, "junction");
    assert.throws(() => inspectChild(junction, "directory"), denied);
    const symlink = join(path, "symlink");
    symlinkSync(file, symlink, "file");
    assert.throws(() => inspectChild(symlink, "file"), denied);
    const hardlink = join(path, "hardlink");
    linkSync(file, hardlink);
    assert.throws(() => inspectChild(hardlink, "file"), denied);
    assert.throws(() => inspectChild(file, "file"), denied);
    assert.throws(
      () =>
        inspectChild(directory, "directory", {
          ...rootIdentity,
          ownerSid: "S-1-5-21-0-0-0-998",
        }),
      denied,
    );
    assert.throws(() => inspectChild(directory, "unknown"), denied);
  });
}
