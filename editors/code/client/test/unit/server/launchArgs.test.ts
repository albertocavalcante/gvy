import { expect } from "chai";
import {
  buildJavaArgs,
  sanitizeVmArgs,
  DEFAULT_SERVER_VM_ARGS,
} from "../../../src/server/launchArgs";

describe("server/launchArgs", () => {
  it("defaults to ExitOnOutOfMemoryError and no -Xmx", () => {
    expect(DEFAULT_SERVER_VM_ARGS).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
    ]);
    expect(sanitizeVmArgs(undefined)).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
    ]);
  });

  it("places vm args before -jar", () => {
    const args = buildJavaArgs({
      jarPath: "/s.jar",
      javaVersion: 21,
      vmArgs: ["-Xmx2g", "-Dfoo=bar"],
    });
    expect(args).to.deep.equal([
      "-Xmx2g",
      "-Dfoo=bar",
      "--enable-native-access=ALL-UNNAMED",
      "-jar",
      "/s.jar",
    ]);
  });

  it("omits native access flag before Java 16", () => {
    const args = buildJavaArgs({ jarPath: "/s.jar", javaVersion: 11, vmArgs: [] });
    expect(args).to.deep.equal(["-jar", "/s.jar"]);
  });

  it("respects an explicitly empty list", () => {
    expect(sanitizeVmArgs([])).to.deep.equal([]);
  });

  it("ignores non-string entries with a warning", () => {
    const warnings: string[] = [];
    const out = sanitizeVmArgs(["-Xmx1g", 5, null, "-Dx=y"], (m) =>
      warnings.push(m),
    );
    expect(out).to.deep.equal(["-Xmx1g", "-Dx=y"]);
    expect(warnings).to.have.length(2);
  });

  it("falls back to defaults for a non-array value", () => {
    const warnings: string[] = [];
    expect(sanitizeVmArgs("-Xmx1g", (m) => warnings.push(m))).to.deep.equal([
      "-XX:+ExitOnOutOfMemoryError",
    ]);
    expect(warnings).to.have.length(1);
  });
});
