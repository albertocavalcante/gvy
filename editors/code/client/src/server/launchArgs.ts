/**
 * Default JVM options for the language server. Final command line is these
 * followed by the user's `groovy.server.vmargs`, so the user wins: HotSpot
 * honours the last occurrence of a -X/-XX option.
 *
 * Why: with no -Xmx the JVM may grow to 1/4 of RAM (4 GB on a 16 GB machine)
 * and G1 keeps the committed heap while idle. Measured on a 16 GB Mac: 1,138 MB
 * footprint with only ~300 MB of heap in use; one full GC dropped it to 699 MB
 * with 116 MB live.
 *
 * - ExitOnOutOfMemoryError: exit so the client's auto-restart can recover.
 * - Xms64m / Xmx2g: small start, bounded growth.
 * - UseG1GC + G1PeriodicGCInterval (JEP 346): idle cycle every 60 s that
 *   returns unused heap to the OS. Our problem is idle retention, so G1 stays
 *   instead of vscode-java's ParallelGC.
 * - Min/MaxHeapFreeRatio 20/40: shrink after GC without the aggressive 5/10
 *   that caused repeated full GCs in vscode-java.
 * - UseStringDeduplication: cheap with G1 for symbol-heavy heaps.
 *
 * G1-only flags (see {@link isG1OnlyFlag}) are dropped when the user selects a
 * different collector, since two collectors abort the JVM at startup
 * ("Multiple garbage collectors selected").
 *
 * Prior art: https://github.com/redhat-developer/vscode-java/pull/1262
 * (ParallelGC, -Xmx2G; peak heap ~520M to ~220M) and
 * https://github.com/redhat-developer/vscode-java/issues/3395 (idle trimming
 * via G1 periodic GC proposed, not shipped). All flags verified on JDK 17, 21.
 */
export const DEFAULT_SERVER_VM_ARGS: readonly string[] = [
  "-XX:+ExitOnOutOfMemoryError",
  "-Xms64m",
  "-Xmx2g",
  "-XX:+UseG1GC",
  "-XX:G1PeriodicGCInterval=60000",
  "-XX:MinHeapFreeRatio=20",
  "-XX:MaxHeapFreeRatio=40",
  "-XX:+UseStringDeduplication",
];

/** Flags that only make sense with G1 (string dedup warns elsewhere on 17). */
export function isG1OnlyFlag(arg: string): boolean {
  return /^-XX:[+-]?(UseG1GC|G1\w+|UseStringDeduplication)(=|$)/.test(arg);
}

/** True when the user picks (or disables) a collector other than our G1. */
export function selectsOtherCollector(userArgs: readonly string[]): boolean {
  return userArgs.some(
    (arg) =>
      /^-XX:\+Use(Serial|Parallel|Z|Shenandoah|Epsilon)GC$/.test(arg) ||
      arg === "-XX:-UseG1GC",
  );
}

/**
 * Options that size the heap. "Last wins" only holds per flag, so when the
 * user sets any of these our -Xms/-Xmx are dropped: e.g. a user -Xmx32m or
 * -Xms4g would otherwise contradict our -Xms64m/-Xmx2g and abort the JVM.
 */
const HEAP_SIZE_FLAG =
  /^(-Xm[sx]|-XX:(Initial|Min|Max)HeapSize=|-XX:(Initial|Min|Max)RAMPercentage=|-XX:MaxRAM=)/;
const HEAP_SIZING_DEFAULT = /^-Xm[sx]/;
/** Min must not exceed Max, so a user value for either replaces both. */
const FREE_RATIO_FLAG = /^-XX:M(in|ax)HeapFreeRatio=/;

/**
 * Splits the JVM option variables the java launcher honours; they are
 * applied alongside our flags and can clash with them in the same way.
 */
export function envJavaOptions(
  env: Readonly<Record<string, string | undefined>>,
): string[] {
  return ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"].flatMap(
    (name) => (env[name] ?? "").split(/\s+/).filter((arg) => arg.length > 0),
  );
}

/**
 * Keeps only string entries of the `groovy.server.vmargs` setting, warning
 * about anything else. A missing value yields an empty list.
 */
export function sanitizeVmArgs(
  value: unknown,
  warn: (message: string) => void = console.warn,
): string[] {
  if (value === undefined || value === null) {
    return [];
  }
  if (!Array.isArray(value)) {
    warn("groovy.server.vmargs must be an array of strings; ignoring it.");
    return [];
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

/**
 * Defaults (unless `useDefaults` is false) followed by the user's options.
 * Only an explicit `false` opts out of the defaults.
 */
export function resolveVmArgs(
  userValue: unknown,
  useDefaults: unknown = true,
  warn?: (message: string) => void,
  envArgs: readonly string[] = [],
): string[] {
  const user = sanitizeVmArgs(userValue, warn);
  if (useDefaults === false) {
    return user;
  }
  // The environment counts as user intent for conflict detection.
  const intent = [...user, ...envArgs];
  const dropG1 = selectsOtherCollector(intent);
  const dropHeap = intent.some((arg) => HEAP_SIZE_FLAG.test(arg));
  const dropRatios = intent.some((arg) => FREE_RATIO_FLAG.test(arg));
  const defaults = DEFAULT_SERVER_VM_ARGS.filter(
    (arg) =>
      !(dropG1 && isG1OnlyFlag(arg)) &&
      !(dropHeap && HEAP_SIZING_DEFAULT.test(arg)) &&
      !(dropRatios && FREE_RATIO_FLAG.test(arg)),
  );
  return [...defaults, ...user];
}

export interface JavaArgsInput {
  jarPath: string;
  javaVersion?: number;
  vmArgs?: unknown;
  /** `groovy.server.vmargsDefaults`; only `false` disables the defaults. */
  useDefaultVmArgs?: unknown;
  /** Options from JAVA_TOOL_OPTIONS and friends; see {@link envJavaOptions}. */
  envArgs?: readonly string[];
  warn?: (message: string) => void;
}

/** Builds the java command line arguments; JVM options precede `-jar`. */
export function buildJavaArgs(input: JavaArgsInput): string[] {
  const args = resolveVmArgs(
    input.vmArgs,
    input.useDefaultVmArgs,
    input.warn,
    input.envArgs,
  );
  // Java 16+ requires --enable-native-access=ALL-UNNAMED for JNA
  // to suppress "WARNING: A restricted method in java.lang.System has been called"
  if (input.javaVersion !== undefined && input.javaVersion >= 16) {
    args.push("--enable-native-access=ALL-UNNAMED");
  }
  args.push("-jar", input.jarPath);
  return args;
}
