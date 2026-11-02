/** Default JVM options: exit on OOM so the client's auto-restart can recover. */
export const DEFAULT_SERVER_VM_ARGS: readonly string[] = [
  "-XX:+ExitOnOutOfMemoryError",
];

/**
 * Keeps only string entries of the `groovy.server.vmargs` setting, warning
 * about anything else. A missing or non-array value yields the defaults.
 */
export function sanitizeVmArgs(
  value: unknown,
  warn: (message: string) => void = console.warn,
): string[] {
  if (value === undefined || value === null) {
    return [...DEFAULT_SERVER_VM_ARGS];
  }
  if (!Array.isArray(value)) {
    warn("groovy.server.vmargs must be an array of strings; using defaults.");
    return [...DEFAULT_SERVER_VM_ARGS];
  }
  const result: string[] = [];
  for (const entry of value) {
    if (typeof entry === "string") {
      result.push(entry);
    } else {
      warn(
        `Ignoring non-string entry in groovy.server.vmargs: ${JSON.stringify(entry)}`,
      );
    }
  }
  return result;
}

export interface JavaArgsInput {
  jarPath: string;
  javaVersion?: number;
  vmArgs?: unknown;
  warn?: (message: string) => void;
}

/** Builds the java command line arguments; JVM options precede `-jar`. */
export function buildJavaArgs(input: JavaArgsInput): string[] {
  const args = sanitizeVmArgs(input.vmArgs, input.warn);
  // Java 16+ requires --enable-native-access=ALL-UNNAMED for JNA
  // to suppress "WARNING: A restricted method in java.lang.System has been called"
  if (input.javaVersion !== undefined && input.javaVersion >= 16) {
    args.push("--enable-native-access=ALL-UNNAMED");
  }
  args.push("-jar", input.jarPath);
  return args;
}
