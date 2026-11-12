// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from "node:child_process";
import { lstatSync } from "node:fs";

const fullyQualified = (path) =>
  typeof path === "string" &&
  (/^[A-Za-z]:[\\/]/.test(path) || /^\\\\[^\\]+\\[^\\]+/.test(path));

const failure = () =>
  new Error("Cannot establish a private Windows provisioning root");

function inspect(mode, path, helperPath) {
  if (
    typeof path !== "string" ||
    !path.isWellFormed() ||
    !fullyQualified(path) ||
    /[\0\r\n]/.test(path) ||
    Buffer.byteLength(path) > 256 * 1024
  )
    throw failure();
  try {
    if (
      typeof helperPath !== "string" ||
      !fullyQualified(helperPath) ||
      /[\0\r\n]/.test(helperPath) ||
      !lstatSync(helperPath).isFile()
    )
      throw failure();
  } catch {
    throw failure();
  }
  const result = spawnSync(helperPath, [mode], {
    input: Buffer.from(path),
    timeout: 10000,
    maxBuffer: 4096,
    shell: false,
    windowsHide: true,
  });
  if (result.error || result.signal || result.status !== 0) throw failure();
  let value;
  try {
    value = JSON.parse(result.stdout.toString("utf8"));
  } catch {
    throw failure();
  }
  const childType = mode.startsWith("inspect-child-")
    ? mode.slice("inspect-child-".length)
    : null;
  const expectedKeys = childType
    ? "fileId,linkCount,ownerSid,ownerSource,principalSid,sizeBytes,type,volumeSerial"
    : "fileId,ownerSid,volumeSerial";
  if (
    !value ||
    Object.keys(value).sort().join(",") !== expectedKeys ||
    typeof value.ownerSid !== "string" ||
    typeof value.fileId !== "string" ||
    typeof value.volumeSerial !== "string" ||
    !/^S-1-(?:\d+-)*\d+$/.test(value.ownerSid) ||
    !/^[0-9a-f]{32}$/.test(value.fileId) ||
    !/^[0-9a-f]{16}$/.test(value.volumeSerial)
  )
    throw failure();
  if (
    childType &&
    (typeof value.principalSid !== "string" ||
      !/^S-1-(?:\d+-)*\d+$/.test(value.principalSid) ||
      !["token-user", "token-owner"].includes(value.ownerSource) ||
      value.type !== childType ||
      typeof value.sizeBytes !== "string" ||
      !/^[0-9a-f]{16}$/.test(value.sizeBytes) ||
      !Number.isSafeInteger(value.linkCount) ||
      value.linkCount < 1 ||
      value.linkCount > 0xffffffff ||
      (childType === "file" && value.linkCount !== 1))
  )
    throw failure();
  return Object.freeze(value);
}

export function createWindowsPrivateRoot(path, { helperPath }) {
  return inspect("create", path, helperPath);
}

export function inspectWindowsPrivateRoot(path, { helperPath }) {
  return inspect("verify", path, helperPath);
}

export function verifyWindowsPrivateRoot(
  path,
  expectedIdentity,
  { helperPath },
) {
  const actual = inspectWindowsPrivateRoot(path, { helperPath });
  if (
    !expectedIdentity ||
    ["fileId", "ownerSid", "volumeSerial"].some(
      (key) => actual[key] !== expectedIdentity[key],
    )
  )
    throw failure();
  return actual;
}

export function inspectWindowsPrivateChild(
  path,
  { helperPath, type, rootIdentity },
) {
  if (!["directory", "file"].includes(type) || !rootIdentity?.ownerSid)
    throw failure();
  const value = inspect(`inspect-child-${type}`, path, helperPath);
  if (value.principalSid !== rootIdentity.ownerSid) throw failure();
  return value;
}
