import * as path from "path";

export interface ProcessInvocation {
  executable: string;
  args: string[];
  shell: boolean;
}

// Characters cmd.exe interprets even inside double quotes, or that would end
// the quoting. Test names containing them cannot be passed safely.
const WINDOWS_SHELL_META = /[\r\n\0"%!^&|<>()]/u;

export type BuildTool = "gradle" | "maven";

/** Identifies the build tool from a launcher path (POSIX or Windows). */
export function buildToolOf(executable: string): BuildTool | undefined {
  const name = path.win32.basename(executable).toLowerCase();
  if (/^gradlew?(\.bat|\.cmd)?$/u.test(name)) return "gradle";
  if (/^mvnw?(\.bat|\.cmd)?$/u.test(name)) return "maven";
  return undefined;
}

/**
 * Decides how to spawn a build-tool command.
 *
 * POSIX never needs a shell: spawn resolves PATH itself and keeps each argument
 * intact, so Spock names with spaces or shell syntax stay a single argument.
 *
 * On Windows, Node refuses to spawn .cmd/.bat files without a shell
 * (CVE-2024-27980), and extensionless names such as `mvn` only resolve to
 * `mvn.cmd` through cmd's PATHEXT lookup. Those go through cmd.exe with every
 * argument quoted, after rejecting characters cmd would still interpret.
 */
export function buildProcessInvocation(
  executable: string,
  args: readonly string[],
  platform: NodeJS.Platform = process.platform,
): ProcessInvocation {
  const ext = path.win32.extname(executable).toLowerCase();
  const needsShell = platform === "win32" && (ext === "" || ext === ".cmd" || ext === ".bat");

  if (!needsShell) {
    return { executable, args: [...args], shell: false };
  }

  for (const value of [executable, ...args]) {
    if (WINDOWS_SHELL_META.test(value)) {
      throw new Error(
        `Command argument is unsafe for the Windows command shell: ${value}`,
      );
    }
  }

  return {
    executable: `"${executable}"`,
    args: args.map((arg) => `"${arg}"`),
    shell: true,
  };
}

/**
 * Returns a copy of `env` with JAVA_HOME set and its bin directory first on
 * the search path. Windows environments spell the key `Path`; reuse whatever
 * spelling exists so the child does not end up with two conflicting entries.
 */
export function withJavaHome(
  env: NodeJS.ProcessEnv,
  javaHome: string,
  platform: NodeJS.Platform = process.platform,
): NodeJS.ProcessEnv {
  const p = platform === "win32" ? path.win32 : path.posix;
  const pathKey =
    Object.keys(env).find((key) => key.toUpperCase() === "PATH") ?? "PATH";
  const current = env[pathKey];
  const bin = p.join(javaHome, "bin");
  return {
    ...env,
    JAVA_HOME: javaHome,
    [pathKey]: current ? `${bin}${p.delimiter}${current}` : bin,
  };
}
