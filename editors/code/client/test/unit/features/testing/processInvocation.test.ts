import * as assert from "assert";
import {
  buildProcessInvocation,
  buildToolOf,
  killProcessTree,
  withJavaHome,
} from "../../../../src/features/testing/processInvocation";

describe("processInvocation", () => {
  describe("buildProcessInvocation", () => {
    it("never uses a shell on POSIX, so names with spaces stay one argument", () => {
      const inv = buildProcessInvocation(
        "mvn",
        ["test", "-Dtest=com.x.Spec#handles empty list"],
        "linux",
      );
      assert.deepStrictEqual(inv, {
        executable: "mvn",
        args: ["test", "-Dtest=com.x.Spec#handles empty list"],
        shell: false,
      });
    });

    it("does not let shell syntax in a test name run commands on POSIX", () => {
      const inv = buildProcessInvocation(
        "/w/mvnw",
        ["-Dtest=S#a; touch pwned"],
        "darwin",
      );
      assert.strictEqual(inv.shell, false);
      assert.deepStrictEqual(inv.args, ["-Dtest=S#a; touch pwned"]);
    });

    it("runs gradlew.bat through cmd on Windows with quoted arguments", () => {
      const inv = buildProcessInvocation(
        "C:\\My Projects\\app\\gradlew.bat",
        ["test", "--tests", "com.x.Spec.adds two numbers"],
        "win32",
      );
      assert.deepStrictEqual(inv, {
        executable: '"C:\\My Projects\\app\\gradlew.bat"',
        args: ['"test"', '"--tests"', '"com.x.Spec.adds two numbers"'],
        shell: true,
      });
    });

    it("resolves extensionless launchers such as mvn through cmd on Windows", () => {
      const inv = buildProcessInvocation("mvn", ["test"], "win32");
      assert.strictEqual(inv.shell, true);
    });

    it("spawns .exe binaries directly on Windows", () => {
      const inv = buildProcessInvocation(
        "C:\\jdk\\bin\\java.exe",
        ["-version"],
        "win32",
      );
      assert.deepStrictEqual(inv, {
        executable: "C:\\jdk\\bin\\java.exe",
        args: ["-version"],
        shell: false,
      });
    });

    it("rejects cmd metacharacters instead of running them", () => {
      assert.throws(
        () =>
          buildProcessInvocation(
            "gradlew.bat",
            ["--tests", "a & calc"],
            "win32",
          ),
        /unsafe for the Windows command shell/,
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

  describe("buildToolOf", () => {
    it("recognizes wrappers and global launchers on every platform", () => {
      assert.strictEqual(buildToolOf("/w/gradlew"), "gradle");
      assert.strictEqual(buildToolOf("C:\\w\\gradlew.bat"), "gradle");
      assert.strictEqual(buildToolOf("gradle"), "gradle");
      assert.strictEqual(buildToolOf("/w/mvnw"), "maven");
      assert.strictEqual(buildToolOf("C:\\w\\mvnw.cmd"), "maven");
      assert.strictEqual(buildToolOf("mvn"), "maven");
      assert.strictEqual(buildToolOf("/usr/bin/java"), undefined);
    });
  });

  describe("withJavaHome", () => {
    it("prepends JAVA_HOME/bin to an existing Windows 'Path' key", () => {
      const env = withJavaHome(
        { Path: "C:\\Windows\\system32" },
        "C:\\jdk-21",
        "win32",
      );
      assert.strictEqual(env.JAVA_HOME, "C:\\jdk-21");
      assert.strictEqual(env.Path, "C:\\jdk-21\\bin;C:\\Windows\\system32");
      assert.ok(!("PATH" in env), "must not add a second PATH variable");
    });

    it("prepends JAVA_HOME/bin to PATH on POSIX", () => {
      const env = withJavaHome({ PATH: "/usr/bin" }, "/opt/jdk", "linux");
      assert.strictEqual(env.PATH, "/opt/jdk/bin:/usr/bin");
    });

    it("creates PATH when none is set", () => {
      const env = withJavaHome({}, "/opt/jdk", "linux");
      assert.strictEqual(env.PATH, "/opt/jdk/bin");
    });
  });

  describe("killProcessTree", () => {
    it("kills the whole cmd.exe tree on Windows", () => {
      const calls: unknown[][] = [];
      const proc = { pid: 42, kill: () => calls.push(["kill"]) };
      killProcessTree(proc, "win32", (...args: unknown[]) => {
        calls.push(args);
      });
      assert.deepStrictEqual(calls, [["taskkill", ["/pid", "42", "/T", "/F"]]]);
    });

    it("signals the process directly on POSIX", () => {
      const calls: string[] = [];
      killProcessTree(
        { pid: 42, kill: () => calls.push("kill") },
        "linux",
        () => {
          calls.push("spawn");
        },
      );
      assert.deepStrictEqual(calls, ["kill"]);
    });
  });
});
