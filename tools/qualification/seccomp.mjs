// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { isAbsolute } from "node:path";

/** Linux x86_64 LP64/LE only. Keep the helper's header-derived validation identical. */
export function ioUringFilter(architecture, byteOrder) {
  if (architecture !== "x64" || byteOrder !== "LE")
    throw new Error("Unsupported seccomp ABI");
  const program = [
    [0x20, 0, 0, 4], // load seccomp_data.arch
    [0x15, 1, 0, 0xc000003e],
    [0x06, 0, 0, 0x80000000], // wrong audit architecture: kill process
    [0x20, 0, 0, 0], // load seccomp_data.nr
    [0x45, 0, 1, 0x40000000], // x32 convention is not qualified
    [0x06, 0, 0, 0x80000000],
    [0x15, 3, 0, 425],
    [0x15, 2, 0, 426],
    [0x15, 1, 0, 427],
    [0x06, 0, 0, 0x7fff0000],
    [0x06, 0, 0, 0x50026], // SECCOMP_RET_ERRNO | ENOSYS
  ];
  const bytes = Buffer.alloc(program.length * 8);
  program.forEach(([code, jt, jf, k], index) => {
    const at = index * 8;
    bytes.writeUInt16LE(code, at);
    bytes[at + 2] = jt;
    bytes[at + 3] = jf;
    bytes.writeUInt32LE(k, at + 4);
  });
  return bytes;
}

export function filteredArgv(helper, profile, argv) {
  if (
    ![helper, profile, argv?.[0]].every(
      (value) =>
        typeof value === "string" && isAbsolute(value) && !value.includes("\0"),
    )
  )
    throw new Error("Expected absolute helper/profile/command paths");
  if (
    !Array.isArray(argv) ||
    !argv.length ||
    argv.length > 128 ||
    argv.some(
      (value) =>
        typeof value !== "string" ||
        value.includes("\0") ||
        value.length >= 8192,
    )
  )
    throw new Error("Invalid command argument");
  return [helper, "--profile", profile, "--", ...argv];
}

export function verifyCanary(value, uid) {
  const names = ["io_uring_setup", "io_uring_enter", "io_uring_register"];
  return (
    value?.uid === uid &&
    value.noNewPrivs === 1 &&
    value.seccomp === 2 &&
    value.forked === true &&
    value.inheritedExtraDescriptors === 0 &&
    ["effective", "permitted", "inheritable", "ambient"].every(
      (name) => value.capabilities?.[name] === "0000000000000000",
    ) &&
    value.calls?.length === names.length &&
    names.every(
      (name, index) =>
        value.calls[index].name === name &&
        value.calls[index].result === -1 &&
        value.calls[index].errno === 38,
    )
  );
}
