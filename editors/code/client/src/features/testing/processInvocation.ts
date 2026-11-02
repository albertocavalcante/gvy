import * as path from "path";
import { spawn } from "child_process";

export interface ProcessInvocation {
  executable: string;
  args: string[];
  shell: boolean;
}

// Characters cmd.exe interprets even inside double quotes (% and, with delayed
// expansion, !), that end the quoting ("), or that end the command line.
// No launcher can receive these safely.
const WINDOWS_QUOTE_BREAKERS = /[\r\n\0"%!]/u;

// Characters cmd.exe treats literally inside double quotes but acts on outside
// them. gradlew.bat expands its own location unquoted (`set DIRNAME=%~dp0`),
// so a launcher path containing these runs commands before any argument is
// read: a project under `C:\x & y\` makes gradlew.bat execute `y`. Maven's
// launchers are held to the same rule. Parentheses are not in this set; a path like
// `C:\Proj (copy)\gradlew.bat` was verified to work (Maven launchers were
// not, so they keep rejecting parentheses too).
const WINDOWS_UNQUOTED_META = /[&|<>^]/u;

// Characters cmd.exe treats literally inside double quotes. gradlew.bat passes
// its arguments on with %* inside quotes, so test names containing these reach
// Gradle intact (verified on Windows 11 with Node 24 and the real gradlew.bat:
// `x) & (echo pwned > f` arrives as one argument and runs nothing). Maven's
// launchers re-read their arguments in other ways and have not been verified,
// so they keep rejecting these.
const WINDOWS_QUOTED_LITERALS = /[&|<>^()]/u;

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
  const needsShell =
    platform === "win32" && (ext === "" || ext === ".cmd" || ext === ".bat");

  if (!needsShell) {
    return { executable, args: [...args], shell: false };
  }

  // Only Gradle's launcher has been verified with these characters.
  const verified = buildToolOf(executable) === "gradle";
  const unsafe = (value: string, isExecutable: boolean): boolean =>
    WINDOWS_QUOTE_BREAKERS.test(value) ||
    // A trailing backslash would escape the closing quote for the program's
    // own command-line parser and merge this argument with the next one.
    value.endsWith("\\") ||
    (isExecutable && WINDOWS_UNQUOTED_META.test(value)) ||
    (!verified && WINDOWS_QUOTED_LITERALS.test(value));

  for (const [index, value] of [executable, ...args].entries()) {
    if (unsafe(value, index === 0)) {
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

/**
 * Stops a build started by buildProcessInvocation. On Windows the child is
 * cmd.exe running a .bat/.cmd launcher, and killing cmd.exe leaves the
 * wrapper's JVM running, so the whole tree is terminated with taskkill.
 */
export function killProcessTree(
  proc: { pid?: number; kill: () => unknown },
  platform: NodeJS.Platform = process.platform,
  spawnFn: (command: string, args: string[]) => unknown = (command, args) =>
    spawn(command, args, { stdio: "ignore" }).on("error", () => {}),
): void {
  if (platform === "win32" && proc.pid !== undefined) {
    spawnFn("taskkill", ["/pid", String(proc.pid), "/T", "/F"]);
  } else {
    proc.kill();
  }
}
