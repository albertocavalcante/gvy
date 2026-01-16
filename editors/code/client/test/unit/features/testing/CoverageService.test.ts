import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("CoverageService", () => {
  let CoverageService: any;
  let coverageService: any;
  let loggerMock: any;
  let testServiceMock: any;
  let fsMock: any;
  let vscodeMock: any;

  beforeEach(() => {
    // Mock VS Code API
    vscodeMock = {
      Uri: {
        file: (path: string) => ({
          fsPath: path,
          toString: () => `file://${path}`,
        }),
        parse: (path: string) => ({
          fsPath: path,
          toString: () => path,
        }),
      },
      Position: class {
        constructor(
          public line: number,
          public character: number,
        ) {}
      },
      BranchCoverage: class {
        constructor(
          public executed: number | boolean,
          public location: any,
          public label?: string,
        ) {}
      },
      StatementCoverage: class {
        constructor(
          public executed: number | boolean,
          public location: any,
          public branches?: any[],
        ) {}
      },
      FileCoverage: {
        fromDetails: (uri: any, details: any[]) => ({ uri, details }),
      },
      OutputChannel: class {},
    };

    // Mock fs
    fsMock = {
      existsSync: sinon.stub(),
      promises: {
        readFile: sinon.stub(),
      },
    };

    // Mock Logger
    loggerMock = {
      appendLine: sinon.stub(),
    };

    // Mock TestService
    testServiceMock = {
      getCoverage: sinon.stub(),
    };

    // Load CoverageService with mocks
    // Load CoverageService with mocks
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const module = (proxyquire as any).noCallThru()(
      "../../../../src/features/testing/CoverageService",
      {
        vscode: vscodeMock,
        fs: fsMock,
      },
    );
    CoverageService = module.CoverageService;
    coverageService = new CoverageService(testServiceMock, loggerMock);
  });

  it("should correctly create multiple branch coverage entries for cb/mb counts", async () => {
    const workspacePath = "/workspace";

    // Mock LSP response with coverage data
    testServiceMock.getCoverage.resolves({
      files: [
        {
          uri: "file:///workspace/src/com/example/MyClass.groovy",
          lines: [
            {
              line: 1,
              covered: true,
              hitCount: 3,
              branchInfo: {
                covered: 2,
                total: 3,
              },
            },
          ],
        },
      ],
      summary: {
        linesTotal: 1,
        linesCovered: 1,
        lineCoveragePercent: 100,
        branchesTotal: 3,
        branchesCovered: 2,
        branchCoveragePercent: 66.67,
      },
    });

    // Mock TestRun
    const testRunMock = {
      addCoverage: sinon.spy(),
    };

    await coverageService.addCoverageToRun(testRunMock, workspacePath);

    assert.ok(
      testRunMock.addCoverage.calledOnce,
      "addCoverage should be called once",
    );
    const coverage = testRunMock.addCoverage.firstCall.args[0];

    // Verify FileCoverage structure
    assert.equal(coverage.details.length, 1, "Should have 1 line of coverage");
    const line = coverage.details[0];

    // Verify Branches: mb=1, cb=2 => Total 3 branches
    assert.ok(line.branches, "Branches should be present");
    assert.equal(
      line.branches.length,
      3,
      "Should have 3 branch entries (2 covered + 1 missed)",
    );

    // Verify Covered Branches (cb=2)
    const coveredBranches = line.branches.filter((b: any) => b.executed === 1);
    assert.equal(coveredBranches.length, 2, "Should have 2 covered branches");

    // Verify Missed Branches (mb=1)
    const missedBranches = line.branches.filter((b: any) => b.executed === 0);
    assert.equal(missedBranches.length, 1, "Should have 1 missed branch");
  });

  it("should handle lines with no branches", async () => {
    const workspacePath = "/workspace";

    // Mock LSP response with line that has no branches
    testServiceMock.getCoverage.resolves({
      files: [
        {
          uri: "file:///workspace/src/com/example/MyClass.groovy",
          lines: [
            {
              line: 2,
              covered: true,
              hitCount: 3,
            },
          ],
        },
      ],
      summary: {
        linesTotal: 1,
        linesCovered: 1,
        lineCoveragePercent: 100,
        branchesTotal: 0,
        branchesCovered: 0,
        branchCoveragePercent: 0,
      },
    });

    const testRunMock = {
      addCoverage: sinon.spy(),
    };

    await coverageService.addCoverageToRun(testRunMock, workspacePath);

    const coverage = testRunMock.addCoverage.firstCall.args[0];
    const line = coverage.details[0];
    assert.strictEqual(
      line.branches,
      undefined,
      "Branches should be undefined for lines without branches",
    );
  });

  it("should handle multiple packages correctly (regex lastIndex reset)", async () => {
    const workspacePath = "/workspace";

    // Mock LSP response with multiple files
    testServiceMock.getCoverage.resolves({
      files: [
        {
          uri: "file:///workspace/src/com/example/pkg1/Class1.groovy",
          lines: [
            {
              line: 1,
              covered: true,
              hitCount: 1,
            },
          ],
        },
        {
          uri: "file:///workspace/src/com/example/pkg2/Class2.groovy",
          lines: [
            {
              line: 5,
              covered: true,
              hitCount: 1,
            },
          ],
        },
      ],
      summary: {
        linesTotal: 2,
        linesCovered: 2,
        lineCoveragePercent: 100,
        branchesTotal: 0,
        branchesCovered: 0,
        branchCoveragePercent: 0,
      },
    });

    const testRunMock = {
      addCoverage: sinon.spy(),
    };

    await coverageService.addCoverageToRun(testRunMock, workspacePath);

    // Should produce 2 file coverage entries
    assert.equal(
      testRunMock.addCoverage.callCount,
      2,
      "Should process both files",
    );

    const firstCall = testRunMock.addCoverage.getCall(0).args[0];
    assert.ok(firstCall.uri.toString().includes("Class1.groovy"));

    const secondCall = testRunMock.addCoverage.getCall(1).args[0];
    assert.ok(secondCall.uri.toString().includes("Class2.groovy"));
  });
});
