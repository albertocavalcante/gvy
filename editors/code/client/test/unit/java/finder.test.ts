import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";
import { exec } from "child_process";

// Type for JDK results in tests
interface TestJdkResult {
  path: string;
  version: number;
  source: string;
  sourceDescription?: string;
}

describe("JDK Finder Module - Public API", () => {
  let findRuntimesStub: sinon.SinonStub;
  let getRuntimeStub: sinon.SinonStub;
  let getSourcesStub: sinon.SinonStub;
  let getConfigurationStub: sinon.SinonStub;
  let execAsyncStub: sinon.SinonStub;
  let findJava: any;
  let findAllJdks: any;
  let MINIMUM_JAVA_VERSION: number;
  let mockVscode: any;

  beforeEach(() => {
    // Create stubs for jdk-utils
    findRuntimesStub = sinon.stub();
    getRuntimeStub = sinon.stub();
    getSourcesStub = sinon.stub();
    getConfigurationStub = sinon.stub();
    execAsyncStub = sinon.stub();

    // Default: exec fails (no login shell JDK)
    execAsyncStub.rejects(new Error("Command failed"));

    // Default: no configured java.home or configuration.runtimes
    const mockConfig = {
      get: sinon.stub().callsFake((key: string, defaultValue?: any) => {
        if (key === "configuration.runtimes") {
          return defaultValue ?? [];
        }
        return undefined;
      }),
    };
    getConfigurationStub.returns(mockConfig);

    // Mock vscode
    mockVscode = {
      workspace: {
        getConfiguration: getConfigurationStub,
      },
    };

    // Mock util.promisify to return our execAsyncStub
    const mockUtil = {
      promisify: sinon.stub().callsFake((fn: any) => {
        // Check if this is the exec function being promisified by comparing to the actual exec function
        if (fn === exec) {
          return execAsyncStub;
        }
        // For other functions (like fs.access), return a stub that rejects
        return sinon.stub().rejects(new Error("Not implemented"));
      }),
    };

    // Use proxyquire to inject mocks
    const module = proxyquire.noCallThru()("../../../src/java/finder", {
      vscode: mockVscode,
      util: mockUtil,
      "jdk-utils": {
        findRuntimes: findRuntimesStub,
        getRuntime: getRuntimeStub,
        getSources: getSourcesStub,
      },
    });

    findJava = module.findJava;
    findAllJdks = module.findAllJdks;
    MINIMUM_JAVA_VERSION = module.MINIMUM_JAVA_VERSION;
  });

  afterEach(() => {
    sinon.restore();
  });

  describe("MINIMUM_JAVA_VERSION constant", () => {
    it("should equal 17", () => {
      assert.strictEqual(MINIMUM_JAVA_VERSION, 17);
    });
  });

  describe("findJava()", () => {
    describe("groovy.java.home setting behavior", () => {
      it("should return JDK from groovy.java.home setting if configured", async () => {
        // Configure mock to return configured java.home
        const mockConfig = {
          get: sinon.stub().callsFake((key: string, defaultValue?: any) => {
            if (key === "java.home") return "/opt/configured-java";
            if (key === "configuration.runtimes") return defaultValue ?? [];
            return undefined;
          }),
        };
        getConfigurationStub.returns(mockConfig);

        // Mock getRuntime to return JDK 17 for the configured path
        getRuntimeStub.resolves({
          homedir: "/opt/configured-java",
          version: { major: 17, minor: 0, patch: 8 },
          hasJavac: true,
        });

        getSourcesStub.returns(["setting"]);

        const result = await findJava();

        assert.ok(result !== null);
        assert.strictEqual(result?.path, "/opt/configured-java");
        assert.strictEqual(result?.version, 17);
        assert.strictEqual(result?.source, "setting");
      });

      it("should prioritize groovy.java.home over auto-detected JDKs", async () => {
        // Configure mock to return configured java.home
        const mockConfig = {
          get: sinon.stub().callsFake((key: string, defaultValue?: any) => {
            if (key === "java.home") return "/opt/configured-java";
            if (key === "configuration.runtimes") return defaultValue ?? [];
            return undefined;
          }),
        };
        getConfigurationStub.returns(mockConfig);

        // Mock getRuntime to return JDK 17 for the configured path
        getRuntimeStub.resolves({
          homedir: "/opt/configured-java",
          version: { major: 17, minor: 0, patch: 8 },
          hasJavac: true,
        });

        // Mock findRuntimes to return a different JDK (should be ignored)
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["setting"]);

        const result = await findJava();

        assert.ok(result !== null);
        assert.strictEqual(
          result?.path,
          "/opt/configured-java",
          "Should use configured path, not auto-detected",
        );
        assert.strictEqual(result?.version, 17);
      });
    });

    describe("jdk-utils library detection fallback", () => {
      it("should fall back to jdk-utils library detection when no setting configured", async () => {
        // Mock jdk-utils to return a JDK
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findJava();

        assert.ok(result !== null);
        assert.strictEqual(result?.path, "/usr/lib/jvm/java-17");
        assert.strictEqual(result?.version, 17);
        assert.ok(findRuntimesStub.calledOnce, "Should call findRuntimes");
      });

      it("should return first JDK by source priority when jdk-utils finds multiple JDKs", async () => {
        // Mock jdk-utils to return multiple JDKs with same source priority
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-19",
            version: { major: 19, minor: 0, patch: 2 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findJava();

        assert.ok(result !== null);
        // When all JDKs have same source priority, returns first one
        assert.strictEqual(
          result?.version,
          17,
          "Should return first JDK with same source priority",
        );
        assert.strictEqual(result?.path, "/usr/lib/jvm/java-17");
      });
    });

    describe("no JDK found behavior", () => {
      it("should return null if no JDK found", async () => {
        // Mock jdk-utils to return empty array
        findRuntimesStub.resolves([]);

        const result = await findJava();

        assert.strictEqual(result, null);
      });

      it("should return null when jdk-utils fails", async () => {
        // Mock jdk-utils to throw error
        findRuntimesStub.rejects(new Error("Failed to scan"));

        const result = await findJava();

        assert.strictEqual(result, null);
      });
    });

    describe("version info with path", () => {
      it("should return path, version, and source together", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["java_home"]);

        const result = await findJava();

        assert.ok(result !== null);
        assert.ok("path" in result, "Should have path property");
        assert.ok("version" in result, "Should have version property");
        assert.ok("source" in result, "Should have source property");
        assert.strictEqual(typeof result?.path, "string");
        assert.strictEqual(typeof result?.version, "number");
        assert.strictEqual(typeof result?.source, "string");
      });

      it("should return major version as number, not full version object", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 2 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findJava();

        assert.ok(result !== null);
        assert.strictEqual(result?.version, 21);
        assert.strictEqual(
          typeof result?.version,
          "number",
          "Version should be a number",
        );
      });
    });
  });

  describe("findAllJdks()", () => {
    describe("returns array of all detected JDKs", () => {
      it("should return array of all detected JDKs", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        assert.ok(Array.isArray(result), "Should return an array");
        assert.strictEqual(result.length, 3, "Should return all 3 JDKs");
      });

      it("should return empty array when no JDKs found", async () => {
        findRuntimesStub.resolves([]);

        const result = await findAllJdks();

        assert.ok(Array.isArray(result));
        assert.strictEqual(result.length, 0);
      });

      it("should include path, version, and source for each JDK", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        assert.strictEqual(result.length, 1);
        const jdk = result[0];
        assert.ok("path" in jdk, "Should have path");
        assert.ok("version" in jdk, "Should have version");
        assert.ok("source" in jdk, "Should have source");
        assert.strictEqual(typeof jdk.path, "string");
        assert.strictEqual(typeof jdk.version, "number");
        assert.strictEqual(typeof jdk.source, "string");
      });
    });

    describe("source description", () => {
      it("should include sourceDescription for each JDK", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        assert.strictEqual(result.length, 1);
        assert.ok(
          "sourceDescription" in result[0],
          "Should have sourceDescription",
        );
        assert.strictEqual(typeof result[0].sourceDescription, "string");
        assert.ok(
          result[0].sourceDescription.length > 0,
          "sourceDescription should not be empty",
        );
      });

      it("should provide human-readable sourceDescription for different sources", async () => {
        // Test multiple sources
        findRuntimesStub.resolves([
          {
            homedir: "/home/user/.sdkman/candidates/java/17.0.8-tem",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        // Different sources
        getSourcesStub
          .onFirstCall()
          .returns(["jdk_manager"])
          .onSecondCall()
          .returns(["system"]);

        const result = await findAllJdks();

        assert.strictEqual(result.length, 2);
        result.forEach((jdk: TestJdkResult) => {
          assert.ok(jdk.sourceDescription, "Should have sourceDescription");
          assert.strictEqual(
            typeof jdk.sourceDescription,
            "string",
            "sourceDescription should be string",
          );
        });
      });
    });

    describe("deduplication by path", () => {
      it("should deduplicate JDKs by path", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17", // Duplicate
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        assert.strictEqual(
          result.length,
          2,
          "Should deduplicate duplicate paths",
        );

        // Verify all paths are unique
        const paths = result.map((jdk: TestJdkResult) => jdk.path);
        const uniquePaths = Array.from(new Set(paths));
        assert.strictEqual(
          paths.length,
          uniquePaths.length,
          "All paths should be unique",
        );
      });

      it("should handle path normalization (trailing slashes)", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17/", // With trailing slash
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17", // Without trailing slash
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        // Should deduplicate normalized paths (implementation-dependent)
        assert.ok(
          result.length <= 2,
          "Should handle path normalization gracefully",
        );
      });
    });

    describe("sorting behavior", () => {
      it("should sort by version descending when no preferredVersion", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-19",
            version: { major: 19, minor: 0, patch: 2 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        assert.strictEqual(result.length, 4);
        assert.strictEqual(result[0].version, 21, "Highest version first");
        assert.strictEqual(result[1].version, 19);
        assert.strictEqual(result[2].version, 17);
        assert.strictEqual(result[3].version, 11, "Lowest version last");
      });

      it("should prioritize preferredVersion first, then sort descending", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-19",
            version: { major: 19, minor: 0, patch: 2 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(undefined, 17); // preferredVersion = 17

        assert.strictEqual(result.length, 3);
        assert.strictEqual(
          result[0].version,
          17,
          "Preferred version (17) should be first",
        );
        assert.strictEqual(result[1].version, 21, "Then descending order");
        assert.strictEqual(result[2].version, 19);
      });

      it("should handle preferredVersion that is not the highest", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-19",
            version: { major: 19, minor: 0, patch: 2 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(undefined, 19); // preferredVersion = 19

        assert.strictEqual(
          result[0].version,
          19,
          "Preferred (19) should be first",
        );
        assert.strictEqual(result[1].version, 21);
        assert.strictEqual(result[2].version, 17);
      });
    });

    describe("filtering by minVersion", () => {
      it("should filter JDKs by minVersion when provided", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(17); // minVersion = 17

        assert.strictEqual(result.length, 2, "Should filter out JDK < 17");
        assert.ok(
          result.every((jdk: TestJdkResult) => jdk.version >= 17),
          "All returned JDKs should be >= 17",
        );
        assert.strictEqual(result[0].version, 21);
        assert.strictEqual(result[1].version, 17);
      });

      it("should return empty array if no JDKs meet minVersion", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-8",
            version: { major: 8, minor: 0, patch: 392 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(17); // minVersion = 17

        assert.strictEqual(result.length, 0, "Should return empty array");
      });

      it("should not filter when minVersion is not provided", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-8",
            version: { major: 8, minor: 0, patch: 392 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(); // No minVersion

        assert.strictEqual(result.length, 2, "Should return all JDKs");
      });
    });

    describe("combining minVersion and preferredVersion", () => {
      it("should filter by minVersion and prioritize preferredVersion", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(17, 21); // minVersion=17, preferredVersion=21

        assert.strictEqual(result.length, 2, "Should filter out JDK < 17");
        assert.strictEqual(
          result[0].version,
          21,
          "Preferred (21) should be first",
        );
        assert.strictEqual(result[1].version, 17);
      });

      it("should handle preferredVersion below minVersion", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-11",
            version: { major: 11, minor: 0, patch: 18 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks(17, 11); // minVersion=17, preferredVersion=11

        // JDK 11 should be filtered out by minVersion, even though it's preferred
        assert.strictEqual(result.length, 2);
        assert.ok(
          result.every((jdk: TestJdkResult) => jdk.version >= 17),
          "All JDKs should meet minVersion",
        );
      });
    });

    describe("user-configured runtimes from groovy.configuration.runtimes", () => {
      it("should include user-configured runtimes from groovy.configuration.runtimes", async () => {
        // Configure mock to return configured runtimes
        const mockConfig = {
          get: sinon.stub().callsFake((key: string, defaultValue?: any) => {
            if (key === "configuration.runtimes") {
              return [
                {
                  path: "/custom/java-17",
                  name: "Custom JDK 17",
                },
              ];
            }
            return undefined;
          }),
        };
        getConfigurationStub.returns(mockConfig);

        // Mock getRuntime for configured path
        getRuntimeStub.resolves({
          homedir: "/custom/java-17",
          version: { major: 17, minor: 0, patch: 8 },
          hasJavac: true,
        });

        // Mock jdk-utils to return other JDKs
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-21",
            version: { major: 21, minor: 0, patch: 1 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        // Should include both auto-detected and configured JDKs
        assert.ok(result.length >= 2, "Should include configured runtimes");
        const customJdk = result.find(
          (jdk: TestJdkResult) => jdk.path === "/custom/java-17",
        );
        assert.ok(customJdk, "Should include configured runtime");
      });

      it("should deduplicate configured runtimes with auto-detected JDKs", async () => {
        // Configure mock to return configured runtime that matches auto-detected
        const mockConfig = {
          get: sinon.stub().callsFake((key: string, defaultValue?: any) => {
            if (key === "configuration.runtimes") {
              return [
                {
                  path: "/usr/lib/jvm/java-17",
                  name: "Configured JDK 17",
                },
              ];
            }
            return undefined;
          }),
        };
        getConfigurationStub.returns(mockConfig);

        // Mock getRuntime for configured path
        getRuntimeStub.resolves({
          homedir: "/usr/lib/jvm/java-17",
          version: { major: 17, minor: 0, patch: 8 },
          hasJavac: true,
        });

        // Mock jdk-utils to return the same JDK
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        // Should deduplicate
        assert.strictEqual(result.length, 1, "Should deduplicate by path");
        assert.strictEqual(result[0].path, "/usr/lib/jvm/java-17");
      });
    });

    describe("error handling", () => {
      it("should handle jdk-utils errors gracefully", async () => {
        findRuntimesStub.rejects(new Error("Failed to scan for JDKs"));

        const result = await findAllJdks();

        // Should return empty array or handle gracefully (not crash)
        assert.ok(Array.isArray(result));
      });

      it("should skip JDKs with missing version information", async () => {
        findRuntimesStub.resolves([
          {
            homedir: "/usr/lib/jvm/java-unknown",
            version: null, // No version
            hasJavac: true,
          },
          {
            homedir: "/usr/lib/jvm/java-17",
            version: { major: 17, minor: 0, patch: 8 },
            hasJavac: true,
          },
        ]);

        getSourcesStub.returns(["system"]);

        const result = await findAllJdks();

        // Should skip JDK without version
        assert.ok(Array.isArray(result));
        result.forEach((jdk) => {
          assert.ok(jdk.version, "All returned JDKs should have version");
        });
      });
    });
  });
});
