import { LanguageClient } from "vscode-languageclient/node";
import { RequestType } from "vscode-languageserver-protocol";

export interface Test {
  test: string;
  line: number;
}

export interface TestSuite {
  uri: string;
  suite: string;
  tests: Test[];
}

/**
 * Build tool name type for type-safe build tool detection.
 */
export type BuildToolName = "gradle" | "maven" | "bsp" | "unknown";

/**
 * Build tool information from the LSP.
 */
export interface BuildToolInfo {
  name: BuildToolName;
  detected: boolean;
  supportsTestExecution: boolean;
  supportsDebug: boolean;
  supportsCoverage: boolean;
}

/**
 * Test command from the LSP.
 */
export interface TestCommand {
  executable: string;
  args: string[];
  cwd: string;
  env?: Record<string, string>;
}

// ============================================================================
// Test Results DTOs (from groovy/getTestResults)
// ============================================================================

/**
 * Status of a test execution.
 */
export type TestResultStatus = "SUCCESS" | "FAILURE" | "SKIPPED" | "ERROR";

/**
 * Result of a single test execution from Surefire XML.
 */
export interface TestResultItem {
  testId: string;
  name: string;
  status: TestResultStatus;
  durationMs: number;
  output?: string;
  failureMessage?: string;
  stackTrace?: string;
  className?: string;
}

/**
 * Summary of test execution.
 */
export interface TestResultSummary {
  total: number;
  passed: number;
  failed: number;
  skipped: number;
  errors: number;
  durationMs: number;
}

/**
 * Response from groovy/getTestResults.
 */
export interface TestResultsResponse {
  results: TestResultItem[];
  summary: TestResultSummary;
}

// ============================================================================
// Coverage DTOs (from groovy/getCoverage)
// ============================================================================

/**
 * Branch coverage information for a line.
 */
export interface BranchInfo {
  covered: number;
  total: number;
}

/**
 * Line coverage information.
 */
export interface LineCoverage {
  line: number;
  covered: boolean;
  hitCount?: number;
  branchInfo?: BranchInfo;
}

/**
 * Coverage summary for a single file.
 */
export interface FileCoverageSummary {
  linesCovered: number;
  linesTotal: number;
  branchesCovered: number;
  branchesTotal: number;
}

/**
 * Coverage data for a single file.
 */
export interface FileCoverageData {
  uri: string;
  lines: LineCoverage[];
  summary: FileCoverageSummary;
}

/**
 * Overall coverage summary.
 */
export interface CoverageSummary {
  lineCoveragePercent: number;
  branchCoveragePercent: number;
  linesCovered: number;
  linesTotal: number;
  branchesCovered: number;
  branchesTotal: number;
}

/**
 * Response from groovy/getCoverage.
 */
export interface CoverageResponse {
  files: FileCoverageData[];
  summary: CoverageSummary;
}

const DiscoverTestsRequest = new RequestType<
  { workspaceUri: string },
  TestSuite[] | null,
  void
>("groovy/discoverTests");

const GetBuildToolInfoRequest = new RequestType<
  { workspaceUri: string },
  BuildToolInfo | null,
  void
>("groovy/getBuildToolInfo");

const RunTestRequest = new RequestType<
  { uri: string; suite: string; test?: string; debug?: boolean },
  TestCommand | null,
  void
>("groovy/runTest");

const GetTestResultsRequest = new RequestType<
  { workspaceUri: string },
  TestResultsResponse | null,
  void
>("groovy/getTestResults");

const GetCoverageRequest = new RequestType<
  { workspaceUri: string },
  CoverageResponse | null,
  void
>("groovy/getCoverage");

export class TestService {
  constructor(private readonly client: LanguageClient) {}

  async discoverTestsInWorkspace(workspaceUri: string): Promise<TestSuite[]> {
    const suites = await this.client.sendRequest(DiscoverTestsRequest, {
      workspaceUri,
    });
    return suites || [];
  }

  /**
   * Get information about the detected build tool.
   */
  async getBuildToolInfo(workspaceUri: string): Promise<BuildToolInfo> {
    const info = await this.client.sendRequest(GetBuildToolInfoRequest, {
      workspaceUri,
    });
    return (
      info || {
        name: "unknown",
        detected: false,
        supportsTestExecution: false,
        supportsDebug: false,
        supportsCoverage: false,
      }
    );
  }

  /**
   * Get a test command from the LSP.
   * Returns null if the build tool doesn't support test execution.
   */
  async getTestCommand(
    uri: string,
    suite: string,
    test?: string,
    debug?: boolean,
  ): Promise<TestCommand | null> {
    return this.client.sendRequest(RunTestRequest, { uri, suite, test, debug });
  }

  /**
   * Get parsed test results from Surefire/Failsafe XML reports.
   * Call this after test execution to retrieve results with output.
   */
  async getTestResults(workspaceUri: string): Promise<TestResultsResponse> {
    const response = await this.client.sendRequest(GetTestResultsRequest, {
      workspaceUri,
    });
    return (
      response || {
        results: [],
        summary: {
          total: 0,
          passed: 0,
          failed: 0,
          skipped: 0,
          errors: 0,
          durationMs: 0,
        },
      }
    );
  }

  /**
   * Get parsed coverage data from JaCoCo XML reports.
   * Call this after running tests with coverage to retrieve coverage data.
   */
  async getCoverage(workspaceUri: string): Promise<CoverageResponse> {
    const response = await this.client.sendRequest(GetCoverageRequest, {
      workspaceUri,
    });
    return (
      response || {
        files: [],
        summary: {
          lineCoveragePercent: 0,
          branchCoveragePercent: 0,
          linesCovered: 0,
          linesTotal: 0,
          branchesCovered: 0,
          branchesTotal: 0,
        },
      }
    );
  }
}
