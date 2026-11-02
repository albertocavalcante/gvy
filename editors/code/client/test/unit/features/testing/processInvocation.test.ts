import * as assert from "assert";
import { buildProcessInvocation } from "../../../../src/features/testing/processInvocation";

describe("buildProcessInvocation", () => {
  it("preserves Maven arguments without a shell on Unix", () => {
    const arg = '-Dtest=Spec#name"; printf unsafe; #';
    assert.deepStrictEqual(
      buildProcessInvocation("mvn", ["test", arg], "linux"),
      {
        executable: "mvn",
        args: ["test", arg],
        shell: false,
      },
    );
  });

  it("quotes safe arguments for a Windows Maven launcher", () => {
    assert.deepStrictEqual(
      buildProcessInvocation(
        "C:\\Program Files\\Maven\\mvn.cmd",
        ["test", "-Dtest=Spec#test name"],
        "win32",
      ),
      {
        executable: '"C:\\Program Files\\Maven\\mvn.cmd"',
        args: ['"test"', '"-Dtest=Spec#test name"'],
        shell: true,
      },
    );
  });

  it("rejects Windows command expansion and separators", () => {
    for (const arg of [
      '-Dtest=Spec#x" & echo unsafe',
      "-Dtest=%PATH%",
      "-Dtest=x\r\necho unsafe",
    ]) {
      assert.throws(
        () => buildProcessInvocation("mvn.cmd", [arg], "win32"),
        /unsafe/,
      );
    }
  });
});
