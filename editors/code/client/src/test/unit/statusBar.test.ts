import * as assert from "assert";
import * as sinon from "sinon";
import * as vscode from "vscode";
import { StatusBarManager } from "../../ui/statusBar";
import {
  GroovyStatusParams,
  GradleJdkIncompatibleError,
  ToolchainProvisioningError,
} from "../../ui/statusUtils";
import * as errorNotificationHandler from "../../ui/errorNotificationHandler";

describe("StatusBar Error Handling", () => {
  let statusBarManager: StatusBarManager;
  let showErrorNotificationStub: sinon.SinonStub;
  let statusBarItem: vscode.StatusBarItem;

  beforeEach(() => {
    // Create a status bar manager
    statusBarManager = new StatusBarManager("1.0.0");

    // Stub the error notification handler
    showErrorNotificationStub = sinon.stub(
      errorNotificationHandler,
      "showErrorNotification",
    );

    // Create a mock status bar item
    statusBarItem = vscode.window.createStatusBarItem(
      vscode.StatusBarAlignment.Left,
      100,
    );
  });

  afterEach(() => {
    statusBarManager.dispose();
    statusBarItem.dispose();
    sinon.restore();
  });

  describe("handleGroovyStatus with errorDetails", () => {
    it("should call showErrorNotification when errorCode and errorDetails are present", () => {
      const params: GroovyStatusParams = {
        health: "error",
        quiescent: true,
        errorCode: "GRADLE_JDK_INCOMPATIBLE",
        errorDetails: {
          type: "GRADLE_JDK_INCOMPATIBLE",
          gradleVersion: "7.4",
          jdkVersion: 21,
          minGradleVersion: "8.5",
          maxJdkVersion: "17",
          suggestions: [
            "Upgrade to Gradle 8.5 or higher",
            "Use JDK 17 or lower",
          ],
        } as GradleJdkIncompatibleError,
      };

      // Simulate receiving a groovy/status notification
      // We need to access the private handleGroovyStatus method, so we use a workaround
      // by triggering the notification through a mock client (or by making the method public for testing)
      // For this test, we'll directly verify the behavior by checking if the notification is called

      // Since handleGroovyStatus is private, we'll test the integration by verifying
      // that showErrorNotification is called with correct parameters when status changes
      (statusBarManager as any).handleGroovyStatus(params);

      assert.ok(showErrorNotificationStub.calledOnce);
      assert.ok(
        showErrorNotificationStub.calledWith(
          "GRADLE_JDK_INCOMPATIBLE",
          params.errorDetails,
        ),
      );
    });

    it("should not call showErrorNotification when errorDetails is missing", () => {
      const params: GroovyStatusParams = {
        health: "error",
        quiescent: true,
        errorCode: "SOME_ERROR",
        // errorDetails is intentionally missing
      };

      (statusBarManager as any).handleGroovyStatus(params);

      assert.ok(showErrorNotificationStub.notCalled);
    });

    it("should not call showErrorNotification when errorCode is missing", () => {
      const params: GroovyStatusParams = {
        health: "error",
        quiescent: true,
        // errorCode is intentionally missing
        errorDetails: {
          type: "GRADLE_JDK_INCOMPATIBLE",
          gradleVersion: "7.4",
          jdkVersion: 21,
          minGradleVersion: "8.5",
          maxJdkVersion: "17",
          suggestions: ["Upgrade to Gradle 8.5 or higher"],
        } as GradleJdkIncompatibleError,
      };

      (statusBarManager as any).handleGroovyStatus(params);

      assert.ok(showErrorNotificationStub.notCalled);
    });

    it("should not call showErrorNotification for success status", () => {
      const params: GroovyStatusParams = {
        health: "ok",
        quiescent: true,
        message: "All good",
      };

      (statusBarManager as any).handleGroovyStatus(params);

      assert.ok(showErrorNotificationStub.notCalled);
    });

    it("should update server state to error when errorCode is present", () => {
      const params: GroovyStatusParams = {
        health: "ok",
        quiescent: true,
        errorCode: "TOOLCHAIN_PROVISIONING_FAILED",
        errorDetails: {
          type: "TOOLCHAIN_PROVISIONING_FAILED",
          requiredVersion: 17,
          vendor: null,
          platform: "Mac OS X aarch64",
          suggestions: ["Set groovy.gradle.javaHome in VS Code settings"],
        } as ToolchainProvisioningError,
      };

      (statusBarManager as any).handleGroovyStatus(params);

      // Verify state is set to error
      assert.strictEqual(statusBarManager.getState(), "error");

      // Verify notification was shown
      assert.ok(showErrorNotificationStub.calledOnce);
    });
  });
});
