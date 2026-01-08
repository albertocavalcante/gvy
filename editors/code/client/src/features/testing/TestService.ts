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
}
