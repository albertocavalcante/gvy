import { expect } from "chai";
import * as sinon from "sinon";
import {
  GradleService,
  GradleTask,
} from "../../../../src/features/gradle/GradleService";
import {
  ErrorCodes,
  RequestType,
  ResponseError,
} from "vscode-languageserver-protocol";
import type { LanguageClient } from "vscode-languageclient/node";

interface MockLanguageClient {
  sendRequest: sinon.SinonStub;
}

describe("GradleService", () => {
  let sandbox: sinon.SinonSandbox;
  let gradleService: GradleService;
  let mockLanguageClient: MockLanguageClient;

  beforeEach(() => {
    sandbox = sinon.createSandbox();
    mockLanguageClient = {
      sendRequest: sandbox.stub(),
    };
    gradleService = new GradleService(
      () => mockLanguageClient as unknown as LanguageClient,
    );
  });

  afterEach(() => {
    sandbox.restore();
  });

  it("should send a gradleTasks request to the LSP", async () => {
    const workspaceUri = "file:///path/to/workspace";
    const expectedTasks: GradleTask[] = [
      { name: "build", group: "build", project: "root" },
      { name: "test", group: "verification", project: "root" },
    ];

    mockLanguageClient.sendRequest.resolves(expectedTasks);

    const tasks = await gradleService.getTasks(workspaceUri);

    expect(mockLanguageClient.sendRequest.calledOnce).to.be.true;
    const args = mockLanguageClient.sendRequest.firstCall.args;

    // Check request type/method name
    const requestType = args[0] as RequestType<unknown, unknown, unknown>;
    expect(requestType.method).to.equal("groovy/gradleTasks");

    // Check request parameters
    expect(args[1]).to.deep.equal({ workspaceUri });

    // Check result
    expect(tasks).to.deep.equal(expectedTasks);
  });

  it("should handle errors from the LSP", async () => {
    const workspaceUri = "file:///path/to/workspace";
    const error = new Error("LSP Gradle Error");
    mockLanguageClient.sendRequest.rejects(error);

    try {
      await gradleService.getTasks(workspaceUri);
      expect.fail("Should have thrown an error");
    } catch (e) {
      expect((e as Error).message).to.equal("LSP Gradle Error");
    }
  });

  it("returns no tasks when the server does not implement the request", async () => {
    mockLanguageClient.sendRequest.rejects(
      new ResponseError(
        ErrorCodes.MethodNotFound,
        "Unsupported request method: groovy/gradleTasks",
      ),
    );

    const tasks = await gradleService.getTasks("file:///path/to/workspace");

    expect(tasks).to.be.an("array").that.is.empty;
  });

  it("rethrows server errors other than a missing handler", async () => {
    mockLanguageClient.sendRequest.rejects(
      new ResponseError(ErrorCodes.InternalError, "Gradle import failed"),
    );

    let error: Error | undefined;
    try {
      await gradleService.getTasks("file:///path/to/workspace");
    } catch (e) {
      error = e as Error;
    }

    expect(error?.message).to.equal("Gradle import failed");
  });

  it("should return an empty array if LSP returns null", async () => {
    const workspaceUri = "file:///path/to/workspace";
    mockLanguageClient.sendRequest.resolves(null);

    const tasks = await gradleService.getTasks(workspaceUri);

    expect(tasks).to.be.an("array").that.is.empty;
  });
  it("uses the current client after a server restart", async () => {
    const restarted = { sendRequest: sandbox.stub().resolves([]) };
    let current: unknown = mockLanguageClient;
    const service = new GradleService(() => current as LanguageClient);
    current = restarted;
    await service.getTasks("file:///w");
    expect(restarted.sendRequest.calledOnce).to.equal(true);
    expect(mockLanguageClient.sendRequest.called).to.equal(false);
  });

  it("rejects clearly when the server is not running", async () => {
    const service = new GradleService(() => undefined);
    let error: Error | undefined;
    try {
      await service.getTasks("file:///w");
    } catch (e) {
      error = e as Error;
    }
    expect(error?.message).to.match(/not running/);
  });
});
