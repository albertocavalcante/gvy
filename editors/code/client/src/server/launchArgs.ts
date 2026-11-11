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
      // Silently selects ParallelGC and sizes the heap.
      arg === "-XX:+AggressiveHeap" ||
      arg === "-XX:-UseG1GC",
  );
}

const MIB = 1024 * 1024;
/** Values of our own -Xms/-Xmx and ratio defaults, used for conflict checks. */
const DEFAULT_XMS_BYTES = 64 * MIB;
const DEFAULT_XMX_BYTES = 2048 * MIB;
const DEFAULT_MIN_FREE_RATIO = 20;
const DEFAULT_MAX_FREE_RATIO = 40;

/** Parses a JVM size such as 512m or 2g; undefined when not a plain size. */
export function parseJvmSize(text: string): number | undefined {
  const match = /^(\d+)([kKmMgGtT]?)$/.exec(text);
  if (!match) {
    return undefined;
  }
  const unit = { "": 1, k: 1024, m: MIB, g: 1024 * MIB, t: 1024 * 1024 * MIB };
  return Number(match[1]) * unit[match[2].toLowerCase() as keyof typeof unit];
}

/**
 * Last size given by any of `flags` (each a prefix such as `-Xmx` or
 * `-XX:MaxHeapSize=`). NaN means present but not a parseable size.
 */
function lastSize(args: readonly string[], flags: readonly string[]): number {
  let result = Number.NaN;
  for (const arg of args) {
    const flag = flags.find((f) => arg.startsWith(f));
    if (flag) {
      result = parseJvmSize(arg.slice(flag.length)) ?? Number.NaN;
    }
  }
  return result;
}

const MAX_HEAP_FLAGS = ["-Xmx", "-XX:MaxHeapSize="];
const MIN_HEAP_FLAGS = ["-Xms", "-XX:InitialHeapSize=", "-XX:MinHeapSize="];
/** Max-heap intent without a size we can compare (percentages, MaxRAM, ...). */
const OTHER_MAX_INTENT =
  /^(-XX:(Max|Min)RAM(Percentage|Fraction)=|-XX:MaxRAM=|-XX:SoftMaxHeapSize=|-XX:\+AggressiveHeap$)/;
const OTHER_MIN_INTENT = /^-XX:Initial(RAMPercentage|RAMFraction)=/;

function present(args: readonly string[], flags: readonly string[]): boolean {
  return args.some((arg) => flags.some((f) => arg.startsWith(f)));
}

/** Percent value of the last MinHeapFreeRatio / -Xminf style option. */
function lastRatio(
  args: readonly string[],
  which: "Min" | "Max",
): number | undefined {
  const xx = `-XX:${which}HeapFreeRatio=`;
  const x = which === "Min" ? "-Xminf" : "-Xmaxf";
  let result: number | undefined;
  for (const arg of args) {
    if (arg.startsWith(xx)) {
      result = Number(arg.slice(xx.length));
    } else if (arg.startsWith(x)) {
      result = Number(arg.slice(x.length)) * 100;
    }
  }
  return result;
}

/**
 * "Last wins" only holds per flag, and HotSpot rejects contradictory sizes, so
 * a default is dropped only when the user's options (or the Java environment
 * variables) would conflict with it:
 * - -Xmx2g when the user sets a maximum, or an initial heap above 2g;
 * - -Xms64m when the user's maximum is below 64m;
 * - MinHeapFreeRatio=20 when the user's Max is below it, and
 *   MaxHeapFreeRatio=40 when the user's Min is above it.
 * An unparseable value drops the default it might conflict with.
 */
function conflictingDefaults(intent: readonly string[]): Set<string> {
  const drop = new Set<string>();
  const userMaxSet =
    present(intent, MAX_HEAP_FLAGS) ||
    intent.some((a) => OTHER_MAX_INTENT.test(a));
  if (userMaxSet) {
    drop.add("-Xmx2g");
    const max = lastSize(intent, MAX_HEAP_FLAGS);
    const otherMax = intent.some((a) => OTHER_MAX_INTENT.test(a));
    // A percentage or MaxRAM cannot be compared; -Xms64m stays safe for those.
    if (
      present(intent, MAX_HEAP_FLAGS) &&
      !otherMax &&
      !(max >= DEFAULT_XMS_BYTES)
    ) {
      drop.add("-Xms64m");
    }
  } else if (
    present(intent, MIN_HEAP_FLAGS) ||
    intent.some((a) => OTHER_MIN_INTENT.test(a))
  ) {
    const min = lastSize(intent, MIN_HEAP_FLAGS);
    if (!(min <= DEFAULT_XMX_BYTES)) {
      drop.add("-Xmx2g");
    }
  }
  const userMin = lastRatio(intent, "Min");
  const userMax = lastRatio(intent, "Max");
  if (userMax !== undefined && !(userMax >= DEFAULT_MIN_FREE_RATIO)) {
    drop.add("-XX:MinHeapFreeRatio=20");
  }
  if (userMin !== undefined && !(userMin <= DEFAULT_MAX_FREE_RATIO)) {
    drop.add("-XX:MaxHeapFreeRatio=40");
  }
  return drop;
}

/**
 * JVM options from the environment, in HotSpot order: JAVA_TOOL_OPTIONS and
 * JDK_JAVA_OPTIONS are processed before the command line, _JAVA_OPTIONS after.
 */
export interface EnvJavaOptions {
  before: readonly string[];
  after: readonly string[];
}

/**
 * Splits the JVM option variables the java launcher honours; they are
 * applied alongside our flags and can clash with them in the same way.
 */
export function envJavaOptions(
  env: Readonly<Record<string, string | undefined>>,
): EnvJavaOptions {
  const split = (name: string): string[] =>
    (env[name] ?? "")
      .split(/\s+/)
      // The JVM strips quotes around options.
      .map((arg) => arg.replace(/^["']+|["']+$/g, ""))
      .filter((arg) => arg.length > 0);
  return {
    before: [...split("JAVA_TOOL_OPTIONS"), ...split("JDK_JAVA_OPTIONS")],
    after: split("_JAVA_OPTIONS"),
  };
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
  env: EnvJavaOptions = { before: [], after: [] },
): string[] {
  const user = sanitizeVmArgs(userValue, warn);
  if (useDefaults === false) {
    return user;
  }
  // The environment counts as user intent for conflict detection, listed in
  // the order HotSpot applies it so that "last wins" evaluates correctly.
  const intent = [...env.before, ...user, ...env.after];
  const dropG1 = selectsOtherCollector(intent);
  const dropped = conflictingDefaults(intent);
  const defaults = DEFAULT_SERVER_VM_ARGS.filter(
    (arg) => !(dropG1 && isG1OnlyFlag(arg)) && !dropped.has(arg),
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
  env?: EnvJavaOptions;
  warn?: (message: string) => void;
}

/** Builds the java command line arguments; JVM options precede `-jar`. */
export function buildJavaArgs(input: JavaArgsInput): string[] {
  const args = resolveVmArgs(
    input.vmArgs,
    input.useDefaultVmArgs,
    input.warn,
    input.env,
  );
  // Java 16+ requires --enable-native-access=ALL-UNNAMED for JNA
  // to suppress "WARNING: A restricted method in java.lang.System has been called"
  if (input.javaVersion !== undefined && input.javaVersion >= 16) {
    args.push("--enable-native-access=ALL-UNNAMED");
  }
  args.push("-jar", input.jarPath);
  return args;
}
