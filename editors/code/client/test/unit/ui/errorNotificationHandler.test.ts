import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";
import {
  GradleJdkIncompatibleError,
  GroovyJdkIncompatibleError,
  ToolchainProvisioningError,
  GenericError,
  ProjectJdkNewerWarning,
} from "../../../src/ui/statusUtils";

describe("Error Notification Handler", () => {
  let showErrorMessageStub: sinon.SinonStub;
  let showWarningMessageStub: sinon.SinonStub;
  let showInformationMessageStub: sinon.SinonStub;
  let executeCommandStub: sinon.SinonStub;
  let getConfigurationStub: sinon.SinonStub;
  let configUpdateStub: sinon.SinonStub;
  let showErrorNotification: typeof import("../../../src/ui/errorNotificationHandler").showErrorNotification;
  let mockVscode: any;

  beforeEach(() => {
    showErrorMessageStub = sinon.stub();
    showWarningMessageStub = sinon.stub();
    showInformationMessageStub = sinon.stub();
    executeCommandStub = sinon.stub();
    configUpdateStub = sinon.stub();
    getConfigurationStub = sinon.stub();

    // By default, return undefined (no action selected)
    showErrorMessageStub.resolves(undefined);
    showWarningMessageStub.resolves(undefined);
    showInformationMessageStub.resolves(undefined);
    executeCommandStub.resolves();
    configUpdateStub.resolves();

    // Mock configuration object
    const mockConfig = {
      get: sinon.stub().returns(false), // Default: warnings not suppressed
      update: configUpdateStub,
    };
    getConfigurationStub.returns(mockConfig);

    mockVscode = {
      window: {
        showErrorMessage: showErrorMessageStub,
        showWarningMessage: showWarningMessageStub,
        showInformationMessage: showInformationMessageStub,
      },
      workspace: {
        getConfiguration: getConfigurationStub,
      },
      commands: {
        executeCommand: executeCommandStub,
      },
      env: {
        openExternal: sinon.stub().resolves(),
        clipboard: {
          writeText: sinon.stub().resolves(),
        },
      },
      Uri: {
        parse: sinon
          .stub()
          .callsFake((url: string) => ({ toString: () => url })),
      },
      ConfigurationTarget: {
        Workspace: 2,
      },
    };

    // Use proxyquire to inject mock vscode
    const module = proxyquire.noCallThru()(
      "../../../src/ui/errorNotificationHandler",
      {
        vscode: mockVscode,
      },
    );
    showErrorNotification = module.showErrorNotification;
  });

  afterEach(() => {
    sinon.restore();
  });

  describe("showErrorNotification", () => {
    it("should show error message when no suggestions are provided", async () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "TEST_ERROR",
        details: null,
        suggestions: [],
      };

      await showErrorNotification("TEST_ERROR", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      assert.ok(
        showErrorMessageStub.calledWith(
          "Groovy Language Server Error: TEST_ERROR",
        ),
      );
    });

    it("should show error message for GRADLE_JDK_INCOMPATIBLE with action buttons", async () => {
      const errorDetails: GradleJdkIncompatibleError = {
        type: "GRADLE_JDK_INCOMPATIBLE",
        gradleVersion: "7.4",
        jdkVersion: 21,
        minGradleVersion: "8.5",
        maxJdkVersion: "17",
        suggestions: ["Upgrade to Gradle 8.5 or higher", "Use JDK 17 or lower"],
      };

      await showErrorNotification("GRADLE_JDK_INCOMPATIBLE", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Gradle 7.4"));
      assert.ok(call.args[0].includes("JDK 21"));
      // With new priority logic (max 3 buttons), only first suggestion is shown
      assert.strictEqual(call.args[1], "Upgrade to Gradle 8.5 or higher");
    });

    it("should show error message for GROOVY_JDK_INCOMPATIBLE with action buttons", async () => {
      const errorDetails: GroovyJdkIncompatibleError = {
        type: "GROOVY_JDK_INCOMPATIBLE",
        groovyVersion: "2.5.0",
        jdkVersion: 17,
        classFileMajorVersion: 61,
        minGroovyVersion: "3.0.0",
        suggestions: ["Upgrade to Groovy 3.0.0 or higher", "Use JDK 8 or 11"],
      };

      await showErrorNotification("GROOVY_JDK_INCOMPATIBLE", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Groovy 2.5.0"));
      assert.ok(call.args[0].includes("JDK 17"));
      // With new priority logic (max 3 buttons), only first suggestion is shown
      assert.strictEqual(call.args[1], "Upgrade to Groovy 3.0.0 or higher");
    });

    it("should show error message for TOOLCHAIN_PROVISIONING_FAILED with smart action buttons", async () => {
      const errorDetails: ToolchainProvisioningError = {
        type: "TOOLCHAIN_PROVISIONING_FAILED",
        requiredVersion: 17,
        vendor: "adoptium",
        platform: "Mac OS X aarch64",
        suggestions: [
          "Set groovy.gradle.javaHome in VS Code settings",
          "Install JDK 17 from https://adoptium.net",
        ],
      };

      await showErrorNotification(
        "TOOLCHAIN_PROVISIONING_FAILED",
        errorDetails,
      );

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Java 17"));
      assert.ok(call.args[0].includes("Mac OS X aarch64"));
      // Smart action buttons for toolchain errors
      assert.strictEqual(call.args[1], "Detect & Set Java");
      assert.strictEqual(call.args[2], "Add Auto-Download Plugin");
    });

    it("should show warning message for generic errors", async () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "CUSTOM_ERROR",
        details: { foo: "bar" },
        suggestions: ["Try this fix", "Or try this other fix"],
      };

      await showErrorNotification("CUSTOM_ERROR", errorDetails);

      assert.ok(showWarningMessageStub.calledOnce);
      const call = showWarningMessageStub.getCall(0);
      assert.ok(call.args[0].includes("CUSTOM_ERROR"));
      // With new priority logic (max 3 buttons), only first suggestion is shown
      assert.strictEqual(call.args[1], "Try this fix");
    });

    it("should limit to 3 action buttons maximum (VS Code UX guideline)", async () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "MANY_SUGGESTIONS",
        details: null,
        suggestions: [
          "First suggestion",
          "Second suggestion",
          "Third suggestion",
          "Fourth suggestion",
        ],
      };

      await showErrorNotification("MANY_SUGGESTIONS", errorDetails);

      assert.ok(showWarningMessageStub.calledOnce);
      const call = showWarningMessageStub.getCall(0);
      // With new priority logic: max 3 buttons, only first suggestion is shown
      assert.strictEqual(call.args[1], "First suggestion");
      // No more than 3 total buttons
      assert.strictEqual(call.args.length, 2); // message + 1 button
    });
  });

  describe("handleActionClick (via user interaction)", () => {
    it("should execute configureJava when Detect & Set Java is clicked", async () => {
      const errorDetails: ToolchainProvisioningError = {
        type: "TOOLCHAIN_PROVISIONING_FAILED",
        requiredVersion: 17,
        vendor: null,
        platform: null,
        suggestions: ["Set groovy.gradle.javaHome in VS Code settings"],
      };

      // Simulate user clicking "Detect & Set Java"
      showErrorMessageStub.resolves("Detect & Set Java");

      await showErrorNotification(
        "TOOLCHAIN_PROVISIONING_FAILED",
        errorDetails,
      );

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should have called configureJava command with requiredVersion
      assert.ok(
        executeCommandStub.calledWith("groovy.configureJava", 17),
        "should call configureJava command with required version",
      );
    });

    it("should execute addFoojayResolver when Add Auto-Download Plugin is clicked", async () => {
      const errorDetails: ToolchainProvisioningError = {
        type: "TOOLCHAIN_PROVISIONING_FAILED",
        requiredVersion: 17,
        vendor: null,
        platform: null,
        suggestions: ["Set groovy.gradle.javaHome in VS Code settings"],
      };

      // Simulate user clicking "Add Auto-Download Plugin"
      showErrorMessageStub.resolves("Add Auto-Download Plugin");

      await showErrorNotification(
        "TOOLCHAIN_PROVISIONING_FAILED",
        errorDetails,
      );

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should have called addFoojayResolver command
      assert.ok(
        executeCommandStub.calledWith("groovy.addFoojayResolver"),
        "should call addFoojayResolver command",
      );
    });

    it("should open settings when action mentions settings (generic error)", async () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "TEST_ERROR",
        details: null,
        suggestions: ["Set groovy.gradle.javaHome in VS Code settings"],
      };

      // Simulate user clicking the button
      showWarningMessageStub.resolves(
        "Set groovy.gradle.javaHome in VS Code settings",
      );

      await showErrorNotification("TEST_ERROR", errorDetails);

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should have called openSettings command
      assert.ok(
        executeCommandStub.calledWith(
          "workbench.action.openSettings",
          "groovy.gradle.javaHome",
        ),
      );
    });
  });

  describe("special action buttons", () => {
    it("should execute retry command and await completion when Retry Resolution is clicked", async () => {
      // Use GRADLE_JDK_INCOMPATIBLE to test "Retry Resolution" button with toolchain errors
      const errorDetails: GradleJdkIncompatibleError = {
        type: "GRADLE_JDK_INCOMPATIBLE",
        gradleVersion: "7.4",
        jdkVersion: 21,
        minGradleVersion: "8.5",
        maxJdkVersion: "17",
        suggestions: ["Upgrade Gradle to 8.5"],
      };

      // Track if executeCommand was awaited by using a deferred promise
      let commandResolved = false;
      const commandPromise = new Promise<void>((resolve) => {
        setTimeout(() => {
          commandResolved = true;
          resolve();
        }, 10);
      });
      executeCommandStub.returns(commandPromise);

      // Simulate user clicking "Retry Resolution"
      showErrorMessageStub.resolves("Retry Resolution");

      await showErrorNotification(
        "GRADLE_JDK_INCOMPATIBLE",
        errorDetails,
        undefined,
        "groovy.retryDependencyResolution",
      );

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Verify executeCommand was called with the retry command
      assert.ok(
        executeCommandStub.calledWith("groovy.retryDependencyResolution"),
        "executeCommand should be called with retry command",
      );

      // The command should have been awaited (resolved)
      assert.ok(
        commandResolved,
        "executeCommand promise should have been awaited",
      );
    });

    it("should show output channel when Show Details is clicked", async () => {
      // Use TOOLCHAIN_PROVISIONING_FAILED which has Show Details as third button
      const errorDetails: ToolchainProvisioningError = {
        type: "TOOLCHAIN_PROVISIONING_FAILED",
        requiredVersion: 17,
        vendor: null,
        platform: null,
        suggestions: ["Install JDK 17"],
      };

      // Create a mock output channel
      const mockOutputChannel = {
        show: sinon.stub(),
        appendLine: sinon.stub(),
        append: sinon.stub(),
        clear: sinon.stub(),
        dispose: sinon.stub(),
        hide: sinon.stub(),
        replace: sinon.stub(),
        name: "Test",
      };

      // Simulate user clicking "Show Details"
      showErrorMessageStub.resolves("Show Details");

      await showErrorNotification(
        "TOOLCHAIN_PROVISIONING_FAILED",
        errorDetails,
        mockOutputChannel as any,
        undefined,
      );

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 20));

      // Verify output channel show was called
      assert.ok(
        mockOutputChannel.show.calledWith(true),
        "output channel show should be called with preserveFocus=true",
      );
    });
  });

  describe("JDK version mismatch warnings (PROJECT_JDK_NEWER_WARNING)", () => {
    it("should suppress warning when jdk.suppressMismatchWarning is true", async () => {
      // Configure mock to return true for suppressMismatchWarning
      const mockConfig = {
        get: sinon.stub().returns(true),
        update: configUpdateStub,
      };
      getConfigurationStub.returns(mockConfig);

      const warningDetails: ProjectJdkNewerWarning = {
        type: "PROJECT_JDK_NEWER_WARNING",
        runningJdkVersion: 21,
        targetJdkVersion: 17,
        configurationSource: "build.gradle",
        suggestions: [],
      };

      await showErrorNotification("PROJECT_JDK_NEWER_WARNING", warningDetails);

      // Wait for async handler
      await new Promise((resolve) => setTimeout(resolve, 20));

      // Warning should be suppressed - no notification shown
      assert.strictEqual(showWarningMessageStub.called, false);
      assert.ok(
        mockConfig.get.calledWith("jdk.suppressMismatchWarning", false),
        "should check suppressMismatchWarning config",
      );
    });

    it("should show warning with Configure Java button and execute command with target version", async () => {
      const warningDetails: ProjectJdkNewerWarning = {
        type: "PROJECT_JDK_NEWER_WARNING",
        runningJdkVersion: 21,
        targetJdkVersion: 17,
        configurationSource: "build.gradle",
        suggestions: [],
      };

      // Simulate user clicking "Configure Java"
      showWarningMessageStub.resolves("Configure Java");

      await showErrorNotification("PROJECT_JDK_NEWER_WARNING", warningDetails);

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should show warning with correct message
      assert.ok(showWarningMessageStub.calledOnce);
      const call = showWarningMessageStub.getCall(0);
      assert.ok(call.args[0].includes("LSP JDK 21"));
      assert.ok(call.args[0].includes("project target 17"));
      assert.ok(call.args[0].includes("build.gradle"));

      // Should have Configure Java, Dismiss, and Don't Show Again buttons
      assert.strictEqual(call.args[1], "Configure Java");
      assert.strictEqual(call.args[2], "Dismiss");
      assert.strictEqual(call.args[3], "Don't Show Again");

      // Should execute configureJava command with target version
      assert.ok(
        executeCommandStub.calledWith("groovy.configureJava", 17),
        "should call configureJava command with target version 17",
      );
    });

    it("should update workspace configuration when Don't Show Again is clicked", async () => {
      const warningDetails: ProjectJdkNewerWarning = {
        type: "PROJECT_JDK_NEWER_WARNING",
        runningJdkVersion: 21,
        targetJdkVersion: 17,
        configurationSource: "build.gradle",
        suggestions: [],
      };

      // Simulate user clicking "Don't Show Again"
      showWarningMessageStub.resolves("Don't Show Again");

      await showErrorNotification("PROJECT_JDK_NEWER_WARNING", warningDetails);

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should update workspace configuration
      assert.ok(
        configUpdateStub.calledWith(
          "jdk.suppressMismatchWarning",
          true,
          2, // ConfigurationTarget.Workspace
        ),
        "should update jdk.suppressMismatchWarning to true in workspace config",
      );

      // Should show confirmation message
      assert.ok(
        showInformationMessageStub.calledWith(
          sinon.match(/JDK version mismatch warnings will be suppressed/),
        ),
        "should show confirmation that warnings are suppressed",
      );
    });

    it("should close notification without action when Dismiss is clicked", async () => {
      const warningDetails: ProjectJdkNewerWarning = {
        type: "PROJECT_JDK_NEWER_WARNING",
        runningJdkVersion: 21,
        targetJdkVersion: 17,
        configurationSource: "build.gradle",
        suggestions: [],
      };

      // Simulate user clicking "Dismiss"
      showWarningMessageStub.resolves("Dismiss");

      await showErrorNotification("PROJECT_JDK_NEWER_WARNING", warningDetails);

      // Wait for async handler to complete
      await new Promise((resolve) => setTimeout(resolve, 50));

      // Should not execute any command
      assert.strictEqual(
        executeCommandStub.called,
        false,
        "should not execute any command when Dismiss is clicked",
      );

      // Should not update configuration
      assert.strictEqual(
        configUpdateStub.called,
        false,
        "should not update configuration when Dismiss is clicked",
      );
    });
  });
});
