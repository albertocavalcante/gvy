import { expect } from "chai";
import {
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

  it("appends user args after the defaults; a user -Xmx replaces the heap defaults", () => {
    const args = buildJavaArgs({
      jarPath: "/s.jar",
      javaVersion: 21,
      vmArgs: ["-Xmx4g", "-Dfoo=bar"],
    });
    expect(args).to.deep.equal([
      ...DEFAULTS.filter((a) => !/^-Xm[sx]/.test(a)),
      "-Xmx4g",
      "-Dfoo=bar",
      "--enable-native-access=ALL-UNNAMED",
      "-jar",
      "/s.jar",
    ]);
    expect(args).to.not.include("-Xmx2g");
  });

  it("drops G1-only defaults when the user selects another collector", () => {
    for (const gc of [
      "-XX:+UseParallelGC",
      "-XX:+UseSerialGC",
      "-XX:+UseZGC",
      "-XX:+UseShenandoahGC",
      "-XX:-UseG1GC",
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

  it("drops default -Xms/-Xmx when the user sizes the heap", () => {
    const rest = DEFAULTS.filter((a) => !/^-Xm[sx]/.test(a));
    for (const flag of [
      "-Xms4g",
      "-Xmx32m",
      "-XX:MaxRAMPercentage=50",
      "-XX:MaxHeapSize=1g",
      "-XX:MaxRAMFraction=8",
    ]) {
      expect(resolveVmArgs([flag]), flag).to.deep.equal([...rest, flag]);
    }
  });

  it("replaces both free ratios when the user sets either", () => {
    const rest = DEFAULTS.filter((a) => !/HeapFreeRatio/.test(a));
    expect(resolveVmArgs(["-XX:MaxHeapFreeRatio=10"])).to.deep.equal([
      ...rest,
      "-XX:MaxHeapFreeRatio=10",
    ]);
    expect(resolveVmArgs(["-XX:MinHeapFreeRatio=50"])).to.deep.equal([
      ...rest,
      "-XX:MinHeapFreeRatio=50",
    ]);
  });

  it("does not treat GC tuning flags as collector selection", () => {
    expect(
      selectsOtherCollector([
        "-XX:+UseMaximumCompactionOnSystemGC",
        "-XX:+UseAdaptiveSizePolicyWithSystemGC",
      ]),
    ).to.equal(false);
  });

  it("treats AggressiveHeap as a collector selection", () => {
    expect(selectsOtherCollector(["-XX:+AggressiveHeap"])).to.equal(true);
    expect(resolveVmArgs(["-XX:+AggressiveHeap"])).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
      "-XX:+AggressiveHeap",
    ]);
  });

  it("strips quotes and reads _JAVA_OPTIONS from the environment", () => {
    expect(
      envJavaOptions({ _JAVA_OPTIONS: "\"-XX:+UseParallelGC\" '-Xmx1g'" }),
    ).to.deep.equal(["-XX:+UseParallelGC", "-Xmx1g"]);
    expect(envJavaOptions({ JAVA_TOOL_OPTIONS: "   " })).to.deep.equal([]);
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
    const args = resolveVmArgs([], true, undefined, env);
    expect(args).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
      "-XX:MinHeapFreeRatio=20",
      "-XX:MaxHeapFreeRatio=40",
    ]);
    expect(envJavaOptions({})).to.deep.equal([]);
  });
});
