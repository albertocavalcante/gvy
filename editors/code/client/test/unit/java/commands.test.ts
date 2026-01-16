import * as sinon from "sinon";
import { assert } from "chai";
import * as proxyquire from "proxyquire";

describe("Java Commands", () => {
  let mockVscode: any;
  let mockFinder: any;
  let mockJdkUtils: any;
  let commandsModule: any;

  beforeEach(() => {
    // Mock vscode module
    mockVscode = {
      window: {
        showQuickPick: sinon.stub().resolves(undefined),
        showOpenDialog: sinon.stub().resolves(undefined),
        showInformationMessage: sinon.stub().resolves(undefined),
        showWarningMessage: sinon.stub().resolves(undefined),
        showErrorMessage: sinon.stub().resolves(undefined),
        withProgress: sinon.stub().callsFake(async (_options, task) => {
          return await task();
        }),
        showTextDocument: sinon.stub().resolves(),
      },
      workspace: {
        getConfiguration: sinon.stub().returns({
          get: sinon.stub().returns(undefined),
          update: sinon.stub().resolves(),
        }),
        workspaceFolders: [
          {
            uri: { fsPath: "/workspace", path: "/workspace" },
            name: "test-workspace",
          },
        ],
        findFiles: sinon.stub().resolves([]),
        openTextDocument: sinon.stub().resolves({
          getText: sinon.stub().returns(""),
        }),
        applyEdit: sinon.stub().resolves(true),
        asRelativePath: sinon.stub().callsFake((uri: any) => uri.path),
        getWorkspaceFolder: sinon.stub().callsFake((uri: any) => ({
          name: "test-workspace",
          uri,
        })),
      },
      commands: {
        executeCommand: sinon.stub().resolves(),
        registerCommand: sinon.stub().callsFake((_cmd, handler) => ({
          dispose: sinon.stub(),
        })),
      },
      Uri: {
        joinPath: sinon.stub().callsFake((base, ...paths) => ({
          fsPath: `${base.fsPath}/${paths.join("/")}`,
          path: `${base.path}/${paths.join("/")}`,
        })),
      },
      QuickPickItemKind: {
        Separator: -1,
      },
      ConfigurationTarget: {
        Workspace: 1,
      },
      ProgressLocation: {
        Notification: 15,
      },
      Position: sinon.stub().callsFake((line, char) => ({ line, char })),
      WorkspaceEdit: sinon.stub().callsFake(() => ({
        createFile: sinon.stub(),
        insert: sinon.stub(),
      })),
    };

    // Mock finder module
    mockFinder = {
      findAllJdks: sinon.stub().resolves([]),
      MINIMUM_JAVA_VERSION: 17,
      JavaResolutionExtended: {},
    };

    // Mock jdk-utils module
    mockJdkUtils = {
      getRuntime: sinon.stub().resolves({
        homedir: "/path/to/jdk",
        version: { major: 17 },
      }),
    };

    // Load commands module with mocked dependencies
    commandsModule = proxyquire.noCallThru()("../../../src/java/commands", {
      vscode: mockVscode,
      "./finder": mockFinder,
      "jdk-utils": mockJdkUtils,
    });
  });

  afterEach(() => {
    sinon.restore();
  });

  describe("configureJava", () => {
    describe("Purpose picker", () => {
      it("should show purpose picker when no purpose provided", async () => {
        mockVscode.window.showQuickPick.resolves(undefined); // User cancels

        await commandsModule.configureJava();

        assert.isTrue(mockVscode.window.showQuickPick.calledOnce);
        const options = mockVscode.window.showQuickPick.firstCall.args[1];
        assert.equal(options.title, "Configure Java Runtime");
        assert.equal(options.placeHolder, "What do you want to configure?");
      });

      it("should skip purpose picker when purpose='lsp' provided", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        // First call should be JDK picker, not purpose picker
        const options = mockVscode.window.showQuickPick.firstCall.args[1];
        assert.equal(options.title, "Configure Language Server Runtime");
      });

      it("should skip purpose picker when purpose='project' provided", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "project");

        // First call should be project JDK picker
        const options = mockVscode.window.showQuickPick.firstCall.args[1];
        assert.equal(options.title, "Select Project Build JDK");
      });
    });

    describe("JDK detection and picker display (LSP mode)", () => {
      it("should show progress notification while detecting JDKs", async () => {
        mockFinder.findAllJdks.resolves([
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ]);
        mockVscode.window.showQuickPick.resolves(undefined); // User cancels

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockVscode.window.withProgress.calledOnce);
        const progressOptions =
          mockVscode.window.withProgress.firstCall.args[0];
        assert.equal(progressOptions.title, "Detecting installed JDKs...");
        assert.equal(
          progressOptions.location,
          mockVscode.ProgressLocation.Notification,
        );
      });

      it("should call findAllJdks during detection", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockFinder.findAllJdks.calledOnce);
      });

      it("should pass requiredVersion to findAllJdks", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(21, "lsp");

        assert.isTrue(mockFinder.findAllJdks.calledWith(undefined, 21));
      });

      it("should show QuickPick with detected JDKs", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
          {
            path: "/jdk21",
            version: 21,
            source: "jdk_manager",
            sourceDescription: "SDKMAN",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockVscode.window.showQuickPick.calledOnce);
        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        // Should have separators, JDK items, and Browse option
        assert.isArray(items);
        assert.isTrue(items.length > 0);
      });

      it("should always include Browse option even when no JDKs found", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const browseItem = items.find((item: any) => item.action === "browse");
        assert.isDefined(browseItem);
        assert.include(browseItem.label, "Browse");
      });

      it("should set appropriate placeholder when requiredVersion provided", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(21, "lsp");

        const options = mockVscode.window.showQuickPick.firstCall.args[1];
        assert.include(
          options.placeHolder,
          "LSP needs 17+, project targets Java 21",
        );
      });

      it("should set appropriate placeholder when no requiredVersion", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        const options = mockVscode.window.showQuickPick.firstCall.args[1];
        assert.include(options.placeHolder, "requires Java 17+");
      });
    });

    describe("JDK grouping and sections (LSP mode)", () => {
      it("should group JDKs into LSP Compatible section (>=17)", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
          {
            path: "/jdk21",
            version: 21,
            source: "jdk_manager",
            sourceDescription: "SDKMAN",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const separators = items.filter(
          (item: any) => item.kind === mockVscode.QuickPickItemKind.Separator,
        );
        const lspCompatible = separators.find((s: any) =>
          s.label.includes("LSP Compatible"),
        );
        assert.isDefined(lspCompatible);
      });

      it("should group JDKs into Cannot Run LSP section (<17)", async () => {
        const mockJdks = [
          {
            path: "/jdk8",
            version: 8,
            source: "system",
            sourceDescription: "PATH",
          },
          {
            path: "/jdk11",
            version: 11,
            source: "system",
            sourceDescription: "System",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const separators = items.filter(
          (item: any) => item.kind === mockVscode.QuickPickItemKind.Separator,
        );
        const cannotRunLsp = separators.find((s: any) =>
          s.label.includes("Cannot Run LSP"),
        );
        assert.isDefined(cannotRunLsp);
      });

      it("should show Best Match section when requiredVersion matches and is LSP compatible", async () => {
        const mockJdks = [
          {
            path: "/jdk21",
            version: 21,
            source: "jdk_manager",
            sourceDescription: "SDKMAN",
          },
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(21, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const separators = items.filter(
          (item: any) => item.kind === mockVscode.QuickPickItemKind.Separator,
        );
        const bestMatch = separators.find((s: any) =>
          s.label.includes("Best Match"),
        );
        assert.isDefined(bestMatch);
        assert.include(bestMatch.label, "Java 21");
      });

      it("should show Project Target Only section when requiredVersion matches but cannot run LSP", async () => {
        const mockJdks = [
          {
            path: "/jdk8",
            version: 8,
            source: "system",
            sourceDescription: "System",
          },
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(8, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const separators = items.filter(
          (item: any) => item.kind === mockVscode.QuickPickItemKind.Separator,
        );
        const projectOnly = separators.find((s: any) =>
          s.label.includes("Project Target Only"),
        );
        assert.isDefined(projectOnly);
      });

      it("should mark recommended JDKs with star icon", async () => {
        const mockJdks = [
          {
            path: "/jdk21",
            version: 21,
            source: "jdk_manager",
            sourceDescription: "SDKMAN",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(21, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const jdkItems = items.filter((item: any) => item.jdk !== undefined);
        const recommendedItem = jdkItems.find((item: any) =>
          item.label.includes("$(star-full)"),
        );
        assert.isDefined(recommendedItem);
      });

      it("should show incompatibility warning for JDKs that cannot run LSP", async () => {
        const mockJdks = [
          {
            path: "/jdk11",
            version: 11,
            source: "system",
            sourceDescription: "System",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        const items = mockVscode.window.showQuickPick.firstCall.args[0];
        const jdkItems = items.filter((item: any) => item.jdk !== undefined);
        const incompatibleItem = jdkItems[0];
        assert.include(incompatibleItem.detail, "$(error)");
        assert.include(incompatibleItem.detail, "Cannot run LSP");
      });
    });

    describe("User selection handling (LSP mode)", () => {
      it("should return false when user cancels picker", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves(undefined);

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
      });

      it("should return false when user selects a separator", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          kind: mockVscode.QuickPickItemKind.Separator,
        });

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
      });

      it("should update workspace settings when JDK selected", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);

        const selectedJdk = mockJdks[0];
        mockVscode.window.showQuickPick.resolves({
          jdk: selectedJdk,
          label: "Java 17",
        });
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(result);
        assert.isTrue(
          mockVscode.workspace.getConfiguration.calledWith("groovy"),
        );
        const config = mockVscode.workspace.getConfiguration.returnValues[0];
        assert.isTrue(
          config.update.calledWith(
            "languageServer.javaHome",
            "/jdk17",
            mockVscode.ConfigurationTarget.Workspace,
          ),
        );
      });

      it("should offer to restart server after configuration", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves({
          jdk: mockJdks[0],
          label: "Java 17",
        });
        mockVscode.window.showInformationMessage.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockVscode.window.showInformationMessage.calledOnce);
        const message =
          mockVscode.window.showInformationMessage.firstCall.args[0];
        assert.include(message, "Language Server Java set to Java 17");
        assert.include(message, "Restart the server");
      });

      it("should restart server when user clicks Restart Server button", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves({
          jdk: mockJdks[0],
          label: "Java 17",
        });
        mockVscode.window.showInformationMessage.resolves("Restart Server");

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(
          mockVscode.commands.executeCommand.calledWith("groovy.restartServer"),
        );
      });

      it("should not restart server when user dismisses message", async () => {
        const mockJdks = [
          {
            path: "/jdk17",
            version: 17,
            source: "java_home",
            sourceDescription: "JAVA_HOME",
          },
        ];
        mockFinder.findAllJdks.resolves(mockJdks);
        mockVscode.window.showQuickPick.resolves({
          jdk: mockJdks[0],
          label: "Java 17",
        });
        mockVscode.window.showInformationMessage.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(mockVscode.commands.executeCommand.called);
      });
    });

    describe("Browse functionality (LSP mode)", () => {
      it("should open folder browser when Browse option selected", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockVscode.window.showOpenDialog.calledOnce);
        const options = mockVscode.window.showOpenDialog.firstCall.args[0];
        assert.isFalse(options.canSelectFiles);
        assert.isTrue(options.canSelectFolders);
        assert.isFalse(options.canSelectMany);
      });

      it("should return false when user cancels folder browser", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves(undefined);

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
      });

      it("should validate selected folder is a valid JDK", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([
          { fsPath: "/selected/jdk" },
        ]);
        mockJdkUtils.getRuntime.resolves({
          homedir: "/selected/jdk",
          version: { major: 17 },
        });
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(result);
        assert.isTrue(mockJdkUtils.getRuntime.calledWith("/selected/jdk"));
      });

      it("should show error when selected folder is not a valid JDK", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([
          { fsPath: "/invalid/path" },
        ]);
        mockJdkUtils.getRuntime.resolves({
          homedir: "/invalid/path",
          version: null, // Invalid JDK
        });

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
        assert.isTrue(mockVscode.window.showErrorMessage.calledOnce);
        const errorMsg = mockVscode.window.showErrorMessage.firstCall.args[0];
        assert.include(errorMsg, "not a valid JDK");
      });

      it("should warn user when selecting JDK < 17 via browse", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([{ fsPath: "/jdk11" }]);
        mockJdkUtils.getRuntime.resolves({
          homedir: "/jdk11",
          version: { major: 11 },
        });
        mockVscode.window.showWarningMessage.resolves(undefined);

        await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(mockVscode.window.showWarningMessage.calledOnce);
        const warningMsg =
          mockVscode.window.showWarningMessage.firstCall.args[0];
        assert.include(warningMsg, "Java 11");
        assert.include(warningMsg, "cannot run the Groovy Language Server");
        assert.include(warningMsg, "requires Java 17+");
      });

      it("should allow user to proceed with JDK < 17 if they confirm", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([{ fsPath: "/jdk11" }]);
        mockJdkUtils.getRuntime.resolves({
          homedir: "/jdk11",
          version: { major: 11 },
        });
        mockVscode.window.showWarningMessage.resolves("Yes, use anyway");
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isTrue(result);
      });

      it("should abort if user declines to use JDK < 17", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([{ fsPath: "/jdk11" }]);
        mockJdkUtils.getRuntime.resolves({
          homedir: "/jdk11",
          version: { major: 11 },
        });
        mockVscode.window.showWarningMessage.resolves("No, choose another");

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
      });

      it("should handle errors during JDK validation", async () => {
        mockFinder.findAllJdks.resolves([]);
        mockVscode.window.showQuickPick.resolves({
          action: "browse",
          label: "Browse...",
        });
        mockVscode.window.showOpenDialog.resolves([{ fsPath: "/bad/path" }]);
        mockJdkUtils.getRuntime.rejects(new Error("Failed to read JDK"));

        const result = await commandsModule.configureJava(undefined, "lsp");

        assert.isFalse(result);
        assert.isTrue(mockVscode.window.showErrorMessage.calledOnce);
        const errorMsg = mockVscode.window.showErrorMessage.firstCall.args[0];
        assert.include(errorMsg, "Failed to validate JDK");
      });
    });
  });

  describe("addFoojayResolver", () => {
    describe("settings.gradle detection", () => {
      it("should search for settings.gradle in workspace", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        mockVscode.workspace.openTextDocument.resolves({
          getText: sinon.stub().returns(""),
          positionAt: sinon.stub().returns({ line: 0, char: 0 }),
        });

        await commandsModule.addFoojayResolver();

        assert.isTrue(mockVscode.workspace.findFiles.calledOnce);
        const pattern = mockVscode.workspace.findFiles.firstCall.args[0];
        assert.include(pattern, "settings.gradle");
      });

      it("should return false when no workspace folder open", async () => {
        mockVscode.workspace.workspaceFolders = undefined;

        const result = await commandsModule.addFoojayResolver();

        assert.isFalse(result);
        assert.isTrue(mockVscode.window.showErrorMessage.calledOnce);
        assert.include(
          mockVscode.window.showErrorMessage.firstCall.args[0],
          "No workspace folder",
        );
      });

      it("should offer to create settings.gradle when none exists", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves(undefined);

        await commandsModule.addFoojayResolver();

        assert.isTrue(mockVscode.window.showWarningMessage.calledOnce);
        const message = mockVscode.window.showWarningMessage.firstCall.args[0];
        assert.include(message, "No settings.gradle file found");
      });

      it("should return false when user cancels creation", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves(undefined);

        const result = await commandsModule.addFoojayResolver();

        assert.isFalse(result);
      });

      it("should let user pick when multiple settings.gradle files found", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
          { path: "/workspace/subproject/settings.gradle" },
        ]);
        mockVscode.window.showQuickPick.resolves(undefined);

        await commandsModule.addFoojayResolver();

        assert.isTrue(mockVscode.window.showQuickPick.calledOnce);
      });

      it("should return false when user cancels picker", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
          { path: "/workspace/subproject/settings.gradle" },
        ]);
        mockVscode.window.showQuickPick.resolves(undefined);

        const result = await commandsModule.addFoojayResolver();

        assert.isFalse(result);
      });
    });

    describe("Plugin insertion", () => {
      it("should add plugin to existing plugins block (Groovy syntax)", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        const mockDocument = {
          getText: sinon.stub().returns("plugins {\n}\n"),
          positionAt: sinon.stub().returns({ line: 1, char: 0 }),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.workspace.applyEdit.calledOnce);
      });

      it("should add plugin to existing plugins block (Kotlin syntax)", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle.kts" },
        ]);
        const mockDocument = {
          getText: sinon.stub().returns("plugins {\n}\n"),
          positionAt: sinon.stub().returns({ line: 1, char: 0 }),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.workspace.applyEdit.calledOnce);
      });

      it("should create new plugins block when none exists", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        const mockDocument = {
          getText: sinon.stub().returns("rootProject.name = 'test'\n"),
          positionAt: sinon.stub().returns({ line: 0, char: 0 }),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);
        mockVscode.window.showInformationMessage.resolves(undefined);

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.workspace.applyEdit.calledOnce);
      });

      it("should detect if foojay plugin already exists (Groovy)", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        const mockDocument = {
          getText: sinon
            .stub()
            .returns(
              "plugins {\n    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'\n}\n",
            ),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.window.showInformationMessage.calledOnce);
        assert.include(
          mockVscode.window.showInformationMessage.firstCall.args[0],
          "already configured",
        );
      });

      it("should detect if foojay plugin already exists (Kotlin)", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle.kts" },
        ]);
        const mockDocument = {
          getText: sinon
            .stub()
            .returns(
              'plugins {\n    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"\n}\n',
            ),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.window.showInformationMessage.calledOnce);
        assert.include(
          mockVscode.window.showInformationMessage.firstCall.args[0],
          "already configured",
        );
      });

      it("should show text document after adding plugin", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        const mockDocument = {
          getText: sinon.stub().returns("plugins {\n}\n"),
          positionAt: sinon.stub().returns({ line: 1, char: 0 }),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);
        mockVscode.window.showInformationMessage.resolves(undefined);

        await commandsModule.addFoojayResolver();

        assert.isTrue(
          mockVscode.window.showTextDocument.calledWith(mockDocument),
        );
      });

      it("should offer to save file after adding plugin", async () => {
        mockVscode.workspace.findFiles.resolves([
          { path: "/workspace/settings.gradle" },
        ]);
        const mockDocument = {
          getText: sinon.stub().returns("plugins {\n}\n"),
          positionAt: sinon.stub().returns({ line: 1, char: 0 }),
          save: sinon.stub().resolves(),
        };
        mockVscode.workspace.openTextDocument.resolves(mockDocument);
        mockVscode.window.showInformationMessage.resolves("Save File");

        await commandsModule.addFoojayResolver();

        assert.isTrue(mockDocument.save.calledOnce);
      });
    });

    describe("Create new settings.gradle", () => {
      it("should create settings.gradle with foojay plugin when user confirms", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves("Create settings.gradle");

        const result = await commandsModule.addFoojayResolver();

        assert.isTrue(result);
        assert.isTrue(mockVscode.workspace.applyEdit.calledOnce);
      });

      it("should include rootProject.name in created file", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves("Create settings.gradle");

        await commandsModule.addFoojayResolver();

        const edit = mockVscode.workspace.applyEdit.firstCall.args[0];
        assert.isTrue(edit.createFile.called);
        assert.isTrue(edit.insert.called);
      });

      it("should show created file", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves("Create settings.gradle");

        await commandsModule.addFoojayResolver();

        assert.isTrue(mockVscode.window.showTextDocument.calledOnce);
      });

      it("should show success message after creation", async () => {
        mockVscode.workspace.findFiles.resolves([]);
        mockVscode.window.showWarningMessage.resolves("Create settings.gradle");

        await commandsModule.addFoojayResolver();

        // Should have two information messages: one for creation confirmation
        const calls = mockVscode.window.showInformationMessage.getCalls();
        const successMessage = calls.find((call: any) =>
          call.args[0].includes("Created settings.gradle"),
        );
        assert.isDefined(successMessage);
      });
    });
  });

  describe("registerJavaCommands", () => {
    it("should register groovy.configureJava command", () => {
      const mockContext = {
        subscriptions: [],
      };

      commandsModule.registerJavaCommands(mockContext);

      assert.isTrue(
        mockVscode.commands.registerCommand.calledWith(
          "groovy.configureJava",
          sinon.match.func,
        ),
      );
    });

    it("should register groovy.addFoojayResolver command", () => {
      const mockContext = {
        subscriptions: [],
      };

      commandsModule.registerJavaCommands(mockContext);

      assert.isTrue(
        mockVscode.commands.registerCommand.calledWith(
          "groovy.addFoojayResolver",
          sinon.match.func,
        ),
      );
    });

    it("should add disposables to context subscriptions", () => {
      const mockContext = {
        subscriptions: [],
      };
      const disposable = { dispose: sinon.stub() };
      mockVscode.commands.registerCommand.returns(disposable);

      commandsModule.registerJavaCommands(mockContext);

      assert.equal(mockContext.subscriptions.length, 2);
    });

    it("should pass requiredVersion parameter to configureJava", async () => {
      const mockContext = {
        subscriptions: [],
      };

      // Register commands
      commandsModule.registerJavaCommands(mockContext);

      // Get the registered handler
      const registerCall = mockVscode.commands.registerCommand
        .getCalls()
        .find((call: any) => call.args[0] === "groovy.configureJava");

      assert.isDefined(
        registerCall,
        "groovy.configureJava should be registered",
      );

      const handler = registerCall.args[1];

      // Setup mocks to track the call
      mockFinder.findAllJdks.resolves([]);
      // First call: purpose picker - select LSP
      // Second call: JDK picker - cancel
      mockVscode.window.showQuickPick
        .onFirstCall()
        .resolves({ purpose: "lsp" });
      mockVscode.window.showQuickPick.onSecondCall().resolves(undefined);

      // Execute the registered handler with a required version
      await handler(21);

      // Verify that findAllJdks was called with the requiredVersion parameter (21)
      // This proves the handler correctly passes the parameter to configureJava
      assert.isTrue(mockFinder.findAllJdks.calledWith(undefined, 21));
    });
  });
});
