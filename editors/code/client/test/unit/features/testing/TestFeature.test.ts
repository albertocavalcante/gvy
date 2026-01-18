import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";

describe("TestFeature", () => {
  let sandbox: sinon.SinonSandbox;
  let mockVscode: any;
  let mockSpockGenerator: any;
  let TestFeatureModule: any;
  let fsStatStub: sinon.SinonStub;
  let fsCreateDirectoryStub: sinon.SinonStub;
  let fsWriteFileStub: sinon.SinonStub;
  let executeCommandStub: sinon.SinonStub;
  let openTextDocumentStub: sinon.SinonStub;
  let showTextDocumentStub: sinon.SinonStub;
  let showErrorMessageStub: sinon.SinonStub;
  let showWarningMessageStub: sinon.SinonStub;
  let registerCommandStub: sinon.SinonStub;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock vscode.workspace.fs APIs
    fsStatStub = sandbox.stub();
    fsCreateDirectoryStub = sandbox.stub().resolves();
    fsWriteFileStub = sandbox.stub().resolves();
    executeCommandStub = sandbox.stub();
    openTextDocumentStub = sandbox.stub();
    showTextDocumentStub = sandbox.stub().resolves();
    showErrorMessageStub = sandbox.stub().resolves();
    showWarningMessageStub = sandbox.stub().resolves();
    registerCommandStub = sandbox.stub().returns({ dispose: sandbox.stub() });

    // Mock TextEncoder
    const mockTextEncoder = {
      encode: (text: string) => Buffer.from(text, "utf-8"),
    };

    // Mock FileSystemError
    class MockFileSystemError extends Error {
      code: string;
      constructor(message: string, code: string) {
        super(message);
        this.code = code;
        this.name = "FileSystemError";
      }
    }

    mockVscode = {
      FileSystemError: MockFileSystemError,
      workspace: {
        fs: {
          stat: fsStatStub,
          createDirectory: fsCreateDirectoryStub,
          writeFile: fsWriteFileStub,
        },
        openTextDocument: openTextDocumentStub,
      },
      window: {
        showErrorMessage: showErrorMessageStub,
        showWarningMessage: showWarningMessageStub,
        showTextDocument: showTextDocumentStub,
        activeTextEditor: undefined,
      },
      commands: {
        registerCommand: registerCommandStub,
        executeCommand: executeCommandStub,
      },
      Uri: {
        file: (path: string) => ({ fsPath: path, toString: () => path }),
      },
      SymbolKind: {
        Class: 4,
        Method: 5,
      },
      TextEncoder: mockTextEncoder,
    };

    // Mock SpockGenerator
    mockSpockGenerator = {
      SpockGenerator: sandbox.stub().returns({
        detectPackage: sandbox.stub().returns("com.example"),
        generateSpec: sandbox.stub().returns("// Generated Spock spec"),
        resolveTestPath: sandbox
          .stub()
          .returns("/project/src/test/groovy/MyClassSpec.groovy"),
      }),
    };

    // Load module with mocked dependencies
    TestFeatureModule = proxyquire.noCallThru()(
      "../../../../src/features/testing/TestFeature",
      {
        vscode: mockVscode,
        "./SpockGenerator": mockSpockGenerator,
      },
    );
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("generateTest command", () => {
    it("should register the groovy.test.generate command", () => {
      new TestFeatureModule.TestFeature();

      assert.strictEqual(registerCommandStub.calledOnce, true);
      assert.strictEqual(
        registerCommandStub.firstCall.args[0],
        "groovy.test.generate",
      );
    });

    it("should use async vscode.workspace.fs.stat to check file existence", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock fs.stat to throw FileNotFound (file doesn't exist)
      const fileNotFoundError = new mockVscode.FileSystemError(
        "File not found",
        "FileNotFound",
      );
      fsStatStub.rejects(fileNotFoundError);

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4, // Class
            name: "MyClass",
            children: [
              { kind: 5, name: "method1" },
              { kind: 5, name: "method2" },
            ],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);
      openTextDocumentStub
        .withArgs(
          sinon.match({
            fsPath: "/project/src/test/groovy/MyClassSpec.groovy",
          }),
        )
        .resolves({
          uri: { fsPath: "/project/src/test/groovy/MyClassSpec.groovy" },
        });

      const _testFeature = new TestFeatureModule.TestFeature();

      // Get the registered command handler
      const commandHandler = registerCommandStub.firstCall.args[1];

      // Execute the command with a URI
      await commandHandler(mockUri);

      // Verify fs.stat was called to check file existence
      assert.strictEqual(
        fsStatStub.calledOnce,
        true,
        "fs.stat should be called once",
      );
      assert.strictEqual(
        fsStatStub.firstCall.args[0].fsPath,
        "/project/src/test/groovy/MyClassSpec.groovy",
      );
    });

    it("should use async vscode.workspace.fs.createDirectory to create test directory", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock fs.stat to throw FileNotFound (file doesn't exist)
      const fileNotFoundError = new mockVscode.FileSystemError(
        "File not found",
        "FileNotFound",
      );
      fsStatStub.rejects(fileNotFoundError);

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);
      openTextDocumentStub
        .withArgs(
          sinon.match({
            fsPath: "/project/src/test/groovy/MyClassSpec.groovy",
          }),
        )
        .resolves({
          uri: { fsPath: "/project/src/test/groovy/MyClassSpec.groovy" },
        });

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify fs.createDirectory was called
      assert.strictEqual(
        fsCreateDirectoryStub.calledOnce,
        true,
        "fs.createDirectory should be called once",
      );
      assert.strictEqual(
        fsCreateDirectoryStub.firstCall.args[0].fsPath,
        "/project/src/test/groovy",
      );
    });

    it("should use async vscode.workspace.fs.writeFile to write test file", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock fs.stat to throw FileNotFound (file doesn't exist)
      const fileNotFoundError = new mockVscode.FileSystemError(
        "File not found",
        "FileNotFound",
      );
      fsStatStub.rejects(fileNotFoundError);

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);
      openTextDocumentStub
        .withArgs(
          sinon.match({
            fsPath: "/project/src/test/groovy/MyClassSpec.groovy",
          }),
        )
        .resolves({
          uri: { fsPath: "/project/src/test/groovy/MyClassSpec.groovy" },
        });

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify fs.writeFile was called
      assert.strictEqual(
        fsWriteFileStub.calledOnce,
        true,
        "fs.writeFile should be called once",
      );
      assert.strictEqual(
        fsWriteFileStub.firstCall.args[0].fsPath,
        "/project/src/test/groovy/MyClassSpec.groovy",
      );

      // Verify content was encoded (should be Uint8Array or Buffer)
      const writtenContent = fsWriteFileStub.firstCall.args[1];
      assert.ok(
        writtenContent instanceof Uint8Array ||
          writtenContent instanceof Buffer,
        "Content should be encoded as Uint8Array or Buffer",
      );
    });

    it("should show warning when test file already exists", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock fs.stat to succeed (file exists)
      fsStatStub.resolves({ type: 1, ctime: 0, mtime: 0, size: 100 });

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify warning was shown
      assert.strictEqual(
        showWarningMessageStub.calledOnce,
        true,
        "Warning should be shown when file exists",
      );
      assert.ok(
        showWarningMessageStub.firstCall.args[0].includes("already exists"),
      );

      // Verify file was NOT written
      assert.strictEqual(
        fsWriteFileStub.called,
        false,
        "fs.writeFile should not be called when file exists",
      );
    });

    it("should handle errors during file operations", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock fs.stat to throw FileNotFound (file doesn't exist)
      const fileNotFoundError = new mockVscode.FileSystemError(
        "File not found",
        "FileNotFound",
      );
      fsStatStub.rejects(fileNotFoundError);

      // Mock fs.createDirectory to throw
      fsCreateDirectoryStub.rejects(new Error("Permission denied"));

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify error message was shown
      assert.strictEqual(
        showErrorMessageStub.calledOnce,
        true,
        "Error message should be shown",
      );
      assert.ok(
        showErrorMessageStub.firstCall.args[0].includes(
          "Failed to generate test",
        ),
      );
    });

    it("should show error when no symbols found", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "// Empty file",
      };

      // Mock document symbol provider to return empty array
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify error message was shown
      assert.strictEqual(
        showErrorMessageStub.calledOnce,
        true,
        "Error message should be shown when no symbols found",
      );
      assert.strictEqual(
        showErrorMessageStub.firstCall.args[0],
        "No symbols found. Ensure the Language Server is ready.",
      );

      // Verify file was NOT written
      assert.strictEqual(fsWriteFileStub.called, false);
    });

    it("should show error when no class found", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example",
      };

      // Mock document symbol provider to return non-class symbols
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          { kind: 5, name: "someFunction" }, // Method, not class
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify error message was shown
      assert.strictEqual(
        showErrorMessageStub.calledOnce,
        true,
        "Error message should be shown when no class found",
      );
      assert.strictEqual(
        showErrorMessageStub.firstCall.args[0],
        "No class found in file.",
      );

      // Verify file was NOT written
      assert.strictEqual(fsWriteFileStub.called, false);
    });

    it("should show error when test path cannot be resolved", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };

      // Mock generator to return null for test path
      const mockGeneratorInstance = mockSpockGenerator.SpockGenerator();
      mockGeneratorInstance.resolveTestPath.returns(null);

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify error message was shown
      assert.strictEqual(showErrorMessageStub.calledOnce, true);
      assert.ok(
        showErrorMessageStub.firstCall.args[0].includes(
          "Could not resolve test path",
        ),
      );

      // Verify file was NOT written
      assert.strictEqual(fsWriteFileStub.called, false);
    });

    it("should show error when no URI provided and no active editor", async () => {
      mockVscode.window.activeTextEditor = undefined;

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      // Call without URI
      await commandHandler(undefined);

      // Verify error message was shown
      assert.strictEqual(showErrorMessageStub.calledOnce, true);
      assert.strictEqual(
        showErrorMessageStub.firstCall.args[0],
        "Open a Groovy file to generate tests.",
      );
    });

    it("should open the generated test file after creation", async () => {
      const mockUri = { fsPath: "/project/src/main/groovy/MyClass.groovy" };
      const mockDocument = {
        uri: mockUri,
        getText: () => "package com.example\nclass MyClass {}",
      };
      const generatedTestUri = {
        fsPath: "/project/src/test/groovy/MyClassSpec.groovy",
      };
      const generatedTestDoc = { uri: generatedTestUri };

      // Mock fs.stat to throw FileNotFound (file doesn't exist)
      const fileNotFoundError = new mockVscode.FileSystemError(
        "File not found",
        "FileNotFound",
      );
      fsStatStub.rejects(fileNotFoundError);

      // Mock document symbol provider
      executeCommandStub
        .withArgs("vscode.executeDocumentSymbolProvider", mockUri)
        .resolves([
          {
            kind: 4,
            name: "MyClass",
            children: [{ kind: 5, name: "method1" }],
          },
        ]);

      openTextDocumentStub.withArgs(mockUri).resolves(mockDocument);
      openTextDocumentStub
        .withArgs(sinon.match({ fsPath: generatedTestUri.fsPath }))
        .resolves(generatedTestDoc);

      const _testFeature = new TestFeatureModule.TestFeature();
      const commandHandler = registerCommandStub.firstCall.args[1];

      await commandHandler(mockUri);

      // Verify the test file was opened
      assert.strictEqual(
        openTextDocumentStub.calledTwice,
        true,
        "openTextDocument should be called twice (source + test)",
      );
      assert.strictEqual(
        showTextDocumentStub.calledOnce,
        true,
        "showTextDocument should be called once",
      );
      assert.strictEqual(
        showTextDocumentStub.firstCall.args[0],
        generatedTestDoc,
      );
    });
  });

  describe("dispose", () => {
    it("should dispose all registered commands", () => {
      const disposeStub = sandbox.stub();
      registerCommandStub.returns({ dispose: disposeStub });

      const _testFeature = new TestFeatureModule.TestFeature();
      _testFeature.dispose();

      assert.strictEqual(
        disposeStub.calledOnce,
        true,
        "Dispose should be called on registered command",
      );
    });
  });
});
