import * as path from "path";

export interface ProcessInvocation {
  executable: string;
  args: string[];
  shell: boolean;
}

const WINDOWS_SHELL_META = /[\r\n\0"%!^&|<>()]/u;

/**
 * Windows needs a command shell for Maven batch launchers. Keep all other
 * invocations on the direct process path and reject shell syntax before using
 * the Windows batch path.
 */
export function buildProcessInvocation(
  executable: string,
  args: readonly string[],
  platform: NodeJS.Platform = process.platform,
): ProcessInvocation {
  const name = (platform === "win32" ? path.win32 : path.posix)
    .basename(executable)
    .toLowerCase();
  const needsWindowsShell =
    platform === "win32" && /^(mvn|mvnw)(\.cmd|\.bat)?$/u.test(name);

  if (!needsWindowsShell) {
    return { executable, args: [...args], shell: false };
  }

  for (const value of [executable, ...args]) {
    if (WINDOWS_SHELL_META.test(value)) {
      throw new Error(
        "Maven command contains characters unsafe for the Windows command shell",
      );
    }
  }

  return {
    executable: `"${executable}"`,
    args: args.map((arg) => `"${arg}"`),
    shell: true,
  };
}
