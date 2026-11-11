import { expect } from "chai";
import {
  parseJvmSize,
  buildJavaArgs,
  sanitizeVmArgs,
  resolveVmArgs,
  envJavaOptions,
  selectsOtherCollector,
  isG1OnlyFlag,
  DEFAULT_SERVER_VM_ARGS,
} from "../../../src/server/launchArgs";

const DEFAULTS = [...DEFAULT_SERVER_VM_ARGS];
const NON_G1 = DEFAULTS.filter((arg) => !isG1OnlyFlag(arg));

describe("server/launchArgs", () => {
  it("defaults keep ExitOnOutOfMemoryError and bound the heap", () => {
    expect(DEFAULTS).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-Xms64m",
      "-Xmx2g",
      "-XX:+UseG1GC",
      "-XX:G1PeriodicGCInterval=60000",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
      "-XX:+UseStringDeduplication",
    ]);
  });

  it("uses only the defaults when vmargs is unset", () => {
    const args = buildJavaArgs({
      jarPath: "/s.jar",
      javaVersion: 21,
      vmArgs: undefined,
    });
    expect(args).to.deep.equal([
      ...DEFAULTS,
      "--enable-native-access=ALL-UNNAMED",
      "-jar",
      "/s.jar",
    ]);
  });

  it("drops G1-only defaults when the user selects another collector", () => {
    for (const gc of [
      "-XX:+UseParallelGC",
      "-XX:+UseSerialGC",
      "-XX:+UseZGC",
      "-XX:+UseShenandoahGC",
    ]) {
      const args = resolveVmArgs([gc, "-Dx=y"]);
      expect(args, gc).to.deep.equal([...NON_G1, gc, "-Dx=y"]);
      expect(args.filter(isG1OnlyFlag).filter((a) => a !== gc)).to.deep.equal(
        [],
      );
    }
    expect(NON_G1).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-Xms64m",
      "-Xmx2g",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
    ]);
  });

  it("keeps the G1 defaults when the user also asks for G1", () => {
    expect(resolveVmArgs(["-XX:+UseG1GC"])).to.deep.equal([
      ...DEFAULTS,
      "-XX:+UseG1GC",
    ]);
  });

  it("passes only the user list when defaults are disabled", () => {
    expect(resolveVmArgs(["-Xmx1g"], false)).to.deep.equal(["-Xmx1g"]);
    expect(resolveVmArgs(undefined, false)).to.deep.equal([]);
    expect(
      buildJavaArgs({
        jarPath: "/s.jar",
        javaVersion: 21,
        vmArgs: ["-XX:+UseParallelGC"],
        useDefaultVmArgs: false,
      }),
    ).to.deep.equal([
      "-XX:+UseParallelGC",
      "--enable-native-access=ALL-UNNAMED",
      "-jar",
      "/s.jar",
    ]);
  });

  it("treats a non-boolean defaults setting as enabled", () => {
    expect(resolveVmArgs([], "false")).to.deep.equal(DEFAULTS);
  });

  it("omits native access flag before Java 16", () => {
    const args = buildJavaArgs({ jarPath: "/s.jar", javaVersion: 11 });
    expect(args).to.deep.equal([...DEFAULTS, "-jar", "/s.jar"]);
    expect(args).to.not.include("--enable-native-access=ALL-UNNAMED");
  });

  it("sanitizes: empty list stays empty, non-strings are ignored", () => {
    expect(sanitizeVmArgs([])).to.deep.equal([]);
    expect(sanitizeVmArgs(undefined)).to.deep.equal([]);
    const warnings: string[] = [];
    const out = sanitizeVmArgs(["-Xmx1g", 5, null, "-Dx=y"], (m) =>
      warnings.push(m),
    );
    expect(out).to.deep.equal(["-Xmx1g", "-Dx=y"]);
    expect(warnings).to.have.length(2);
  });

  it("ignores a non-array value with a warning", () => {
    const warnings: string[] = [];
    expect(sanitizeVmArgs("-Xmx1g", (m) => warnings.push(m))).to.deep.equal([]);
    expect(warnings).to.have.length(1);
    expect(resolveVmArgs("-Xmx1g", true, () => undefined)).to.deep.equal(
      DEFAULTS,
    );
  });

  it("does not treat GC tuning flags as collector selection", () => {
    expect(
      selectsOtherCollector([
        "-XX:+UseMaximumCompactionOnSystemGC",
        "-XX:+UseAdaptiveSizePolicyWithSystemGC",
      ]),
    ).to.equal(false);
  });

  it("strips quotes and reads _JAVA_OPTIONS from the environment", () => {
    expect(
      envJavaOptions({ _JAVA_OPTIONS: "\"-XX:+UseParallelGC\" '-Xmx1g'" }),
    ).to.deep.equal(["-XX:+UseParallelGC", "-Xmx1g"]);
    expect(envJavaOptions({ JAVA_TOOL_OPTIONS: "   " })).to.deep.equal([]);
  });

  const without = (...drop: string[]) =>
    DEFAULTS.filter((arg) => !drop.includes(arg));

  it("appends user args after the defaults; user -Xmx replaces only -Xmx2g", () => {
    const args = buildJavaArgs({
      jarPath: "/s.jar",
      javaVersion: 21,
      vmArgs: ["-Xmx4g", "-Dfoo=bar"],
    });
    expect(args).to.deep.equal([
      ...without("-Xmx2g"),
      "-Xmx4g",
      "-Dfoo=bar",
      "--enable-native-access=ALL-UNNAMED",
      "-jar",
      "/s.jar",
    ]);
    expect(args).to.include("-Xms64m");
  });

  it("keeps -Xmx2g and the rest when only the initial heap is small", () => {
    expect(resolveVmArgs(["-Xms128m"])).to.deep.equal([
      ...DEFAULTS,
      "-Xms128m",
    ]);
  });

  it("drops -Xmx2g for an initial heap above it, or an unparseable one", () => {
    for (const flag of [
      "-Xms4g",
      "-XX:InitialHeapSize=3g",
      "-XX:InitialRAMPercentage=50",
    ]) {
      expect(resolveVmArgs([flag]), flag).to.deep.equal([
        ...without("-Xmx2g"),
        flag,
      ]);
    }
  });

  it("drops -Xms64m only when the user maximum is below it", () => {
    expect(resolveVmArgs(["-Xmx32m"])).to.deep.equal([
      ...without("-Xms64m", "-Xmx2g"),
      "-Xmx32m",
    ]);
    expect(resolveVmArgs(["-Xmx64m"])).to.deep.equal([
      ...without("-Xmx2g"),
      "-Xmx64m",
    ]);
    expect(resolveVmArgs(["-XX:MaxHeapSize=1g"])).to.deep.equal([
      ...without("-Xmx2g"),
      "-XX:MaxHeapSize=1g",
    ]);
  });

  it("MaxRAMPercentage-style options replace only -Xmx2g", () => {
    for (const flag of [
      "-XX:MaxRAMPercentage=50",
      "-XX:MaxRAMFraction=8",
      "-XX:MaxRAM=4g",
      "-XX:SoftMaxHeapSize=3g",
    ]) {
      expect(resolveVmArgs([flag]), flag).to.deep.equal([
        ...without("-Xmx2g"),
        flag,
      ]);
    }
  });

  it("AggressiveHeap replaces -Xmx2g and the G1 defaults", () => {
    expect(selectsOtherCollector(["-XX:+AggressiveHeap"])).to.equal(true);
    expect(resolveVmArgs(["-XX:+AggressiveHeap"])).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-Xms64m",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
      "-XX:+AggressiveHeap",
    ]);
  });

  it("drops a free ratio default only when it would contradict the user's", () => {
    // Max above our Min keeps both our ratios (the user's Max still wins).
    expect(resolveVmArgs(["-XX:MaxHeapFreeRatio=30"])).to.deep.equal([
      ...DEFAULTS,
      "-XX:MaxHeapFreeRatio=30",
    ]);
    expect(resolveVmArgs(["-XX:MaxHeapFreeRatio=10"])).to.deep.equal([
      ...without("-XX:MinHeapFreeRatio=20"),
      "-XX:MaxHeapFreeRatio=10",
    ]);
    expect(resolveVmArgs(["-XX:MinHeapFreeRatio=30"])).to.deep.equal([
      ...DEFAULTS,
      "-XX:MinHeapFreeRatio=30",
    ]);
    expect(resolveVmArgs(["-XX:MinHeapFreeRatio=50"])).to.deep.equal([
      ...without("-XX:MaxHeapFreeRatio=40"),
      "-XX:MinHeapFreeRatio=50",
    ]);
  });

  it("recognises the -Xminf/-Xmaxf aliases", () => {
    expect(resolveVmArgs(["-Xminf0.5"])).to.deep.equal([
      ...without("-XX:MaxHeapFreeRatio=40"),
      "-Xminf0.5",
    ]);
    expect(resolveVmArgs(["-Xmaxf0.1"])).to.deep.equal([
      ...without("-XX:MinHeapFreeRatio=20"),
      "-Xmaxf0.1",
    ]);
  });

  it("parses JVM sizes", () => {
    expect(parseJvmSize("64m")).to.equal(64 * 1024 * 1024);
    expect(parseJvmSize("2G")).to.equal(2 * 1024 ** 3);
    expect(parseJvmSize("512")).to.equal(512);
    expect(parseJvmSize("1.5g")).to.equal(undefined);
    expect(parseJvmSize("")).to.equal(undefined);
  });

  it("honours JVM options set through the environment", () => {
    const env = envJavaOptions({
      JAVA_TOOL_OPTIONS: "-XX:+UseParallelGC  -Dx=y",
      JDK_JAVA_OPTIONS: "-XX:MaxRAMPercentage=12.5",
    });
    expect(env).to.deep.equal([
      "-XX:+UseParallelGC",
      "-Dx=y",
      "-XX:MaxRAMPercentage=12.5",
    ]);
    // MaxRAMPercentage conflicts with -Xmx2g only; -Xms64m stays.
    expect(resolveVmArgs([], true, undefined, env)).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-Xms64m",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
    ]);
    expect(envJavaOptions({})).to.deep.equal([]);
  });
});
