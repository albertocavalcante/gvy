import * as assert from "assert";
import * as vscode from "vscode";
import * as sinon from "sinon";
import { showErrorNotification } from "../../ui/errorNotificationHandler";
import {
  GradleJdkIncompatibleError,
  GroovyJdkIncompatibleError,
  ToolchainProvisioningError,
  GenericError,
} from "../../ui/statusUtils";

describe("Error Notification Handler", () => {
  let showErrorMessageStub: sinon.SinonStub;
  let showWarningMessageStub: sinon.SinonStub;
  let executeCommandStub: sinon.SinonStub;

  beforeEach(() => {
    showErrorMessageStub = sinon.stub(vscode.window, "showErrorMessage");
    showWarningMessageStub = sinon.stub(vscode.window, "showWarningMessage");
    executeCommandStub = sinon.stub(vscode.commands, "executeCommand");

    // By default, return undefined (no action selected)
    showErrorMessageStub.resolves(undefined);
    showWarningMessageStub.resolves(undefined);
  });

  afterEach(() => {
    sinon.restore();
  });

  describe("showErrorNotification", () => {
    it("should show error message when no suggestions are provided", () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "TEST_ERROR",
        details: null,
        suggestions: [],
      };

      showErrorNotification("TEST_ERROR", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      assert.ok(
        showErrorMessageStub.calledWith(
          "Groovy Language Server Error: TEST_ERROR",
        ),
      );
    });

    it("should show error message for GRADLE_JDK_INCOMPATIBLE with action buttons", () => {
      const errorDetails: GradleJdkIncompatibleError = {
        type: "GRADLE_JDK_INCOMPATIBLE",
        gradleVersion: "7.4",
        jdkVersion: 21,
        minGradleVersion: "8.5",
        maxJdkVersion: "17",
        suggestions: ["Upgrade to Gradle 8.5 or higher", "Use JDK 17 or lower"],
      };

      showErrorNotification("GRADLE_JDK_INCOMPATIBLE", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Gradle 7.4"));
      assert.ok(call.args[0].includes("JDK 21"));
      assert.strictEqual(call.args[1], "Upgrade to Gradle 8.5 or higher");
      assert.strictEqual(call.args[2], "Use JDK 17 or lower");
    });

    it("should show error message for GROOVY_JDK_INCOMPATIBLE with action buttons", () => {
      const errorDetails: GroovyJdkIncompatibleError = {
        type: "GROOVY_JDK_INCOMPATIBLE",
        groovyVersion: "2.5.0",
        jdkVersion: 17,
        classFileMajorVersion: 61,
        minGroovyVersion: "3.0.0",
        suggestions: ["Upgrade to Groovy 3.0.0 or higher", "Use JDK 8 or 11"],
      };

      showErrorNotification("GROOVY_JDK_INCOMPATIBLE", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Groovy 2.5.0"));
      assert.ok(call.args[0].includes("JDK 17"));
      assert.strictEqual(call.args[1], "Upgrade to Groovy 3.0.0 or higher");
      assert.strictEqual(call.args[2], "Use JDK 8 or 11");
    });

    it("should show error message for TOOLCHAIN_PROVISIONING_FAILED with action buttons", () => {
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

      showErrorNotification("TOOLCHAIN_PROVISIONING_FAILED", errorDetails);

      assert.ok(showErrorMessageStub.calledOnce);
      const call = showErrorMessageStub.getCall(0);
      assert.ok(call.args[0].includes("Java 17"));
      assert.ok(call.args[0].includes("Mac OS X aarch64"));
      assert.strictEqual(
        call.args[1],
        "Set groovy.gradle.javaHome in VS Code settings",
      );
      assert.strictEqual(
        call.args[2],
        "Install JDK 17 from https://adoptium.net",
      );
    });

    it("should show warning message for generic errors", () => {
      const errorDetails: GenericError = {
        type: "GENERIC",
        errorCode: "CUSTOM_ERROR",
        details: { foo: "bar" },
        suggestions: ["Try this fix", "Or try this other fix"],
      };

      showErrorNotification("CUSTOM_ERROR", errorDetails);

      assert.ok(showWarningMessageStub.calledOnce);
      const call = showWarningMessageStub.getCall(0);
      assert.ok(call.args[0].includes("CUSTOM_ERROR"));
      assert.strictEqual(call.args[1], "Try this fix");
      assert.strictEqual(call.args[2], "Or try this other fix");
    });

    it("should limit to 2 action buttons even with more suggestions", () => {
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

      showErrorNotification("MANY_SUGGESTIONS", errorDetails);

      assert.ok(showWarningMessageStub.calledOnce);
      const call = showWarningMessageStub.getCall(0);
      // Should only have 2 action buttons (args[1] and args[2])
      assert.strictEqual(call.args[1], "First suggestion");
      assert.strictEqual(call.args[2], "Second suggestion");
      assert.strictEqual(call.args[3], undefined);
    });
  });

  describe("handleActionClick (via user interaction)", () => {
    it("should open settings when action mentions settings", async () => {
      const errorDetails: ToolchainProvisioningError = {
        type: "TOOLCHAIN_PROVISIONING_FAILED",
        requiredVersion: 17,
        vendor: null,
        platform: null,
        suggestions: ["Set groovy.gradle.javaHome in VS Code settings"],
      };

      // Simulate user clicking the button
      showErrorMessageStub.resolves(
        "Set groovy.gradle.javaHome in VS Code settings",
      );

      showErrorNotification("TOOLCHAIN_PROVISIONING_FAILED", errorDetails);

      // Wait for the promise to resolve
      await showErrorMessageStub.returnValues[0];

      // Should have called openSettings command
      assert.ok(
        executeCommandStub.calledWith(
          "workbench.action.openSettings",
          "groovy.gradle.javaHome",
        ),
      );
    });
  });
});
