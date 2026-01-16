import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("TestCodeLensProvider", () => {
  let TestCodeLensProvider: any;
  let provider: any;
  let testServiceMock: any;
  let vscodeMock: any;
  let documentMock: any;
  let sandbox: sinon.SinonSandbox;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock VS Code API
    vscodeMock = {
      Range: class Range {
        constructor(
          public start: any,
          public end: any,
        ) {}
      },
      Position: class Position {
        constructor(
          public line: number,
          public character: number,
        ) {}
      },
      CodeLens: class CodeLens {
        constructor(
          public range: any,
          public command?: any,
        ) {}
      },
      Uri: {
        parse: sandbox.stub().callsFake((uri: string) => ({
          toString: () => uri,
          fsPath: uri.replace("file://", ""),
        })),
      },
      workspace: {
        getConfiguration: sandbox.stub().callsFake((section: string) => ({
          get: sandbox
            .stub()
            .callsFake((key: string, defaultValue: any) => defaultValue),
        })),
      },
    };

    // Mock TestService
    testServiceMock = {
      discoverTestsInWorkspace: sandbox.stub(),
    };

    // Mock TextDocument
    documentMock = {
      uri: {
        toString: () => "file:///test/MySpec.groovy",
        fsPath: "/test/MySpec.groovy",
      },
      getText: sandbox.stub(),
      lineAt: sandbox.stub(),
    };

    // Use proxyquire to inject mocks
    const module = (proxyquire as any).noCallThru()(
      "../../../../src/features/testing/TestCodeLensProvider",
      {
        vscode: vscodeMock,
      },
    );
    TestCodeLensProvider = module.TestCodeLensProvider;
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("provideCodeLenses", () => {
    it("should return empty array for non-test files", async () => {
      documentMock.uri = {
        toString: () => "file:///test/MyClass.groovy",
        fsPath: "/test/MyClass.groovy",
      };
      documentMock.getText.returns("class MyClass {\n  def method() {}\n}");

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.strictEqual(codeLenses.length, 0);
    });

    it("should return empty array for empty file", async () => {
      documentMock.getText.returns("");

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.strictEqual(codeLenses.length, 0);
    });

    it("should return CodeLens for Spock test class", async () => {
      documentMock.getText.returns(
        "package com.example\n" +
          "import spock.lang.Specification\n" +
          "\n" +
          "class MySpec extends Specification {\n" +
          '  def "test something"() {\n' +
          "    expect:\n" +
          "    true\n" +
          "  }\n" +
          "}",
      );
      documentMock.lineAt
        .withArgs(3)
        .returns({ text: "class MySpec extends Specification {" });

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      // Should have CodeLens for class (Run All | Debug All | Coverage) and test method (Run | Debug | Coverage)
      assert.ok(
        codeLenses.length >= 3,
        `Expected at least 3 CodeLenses, got ${codeLenses.length}`,
      );

      // Check class-level CodeLens (with codicons)
      const classLens = codeLenses.find(
        (lens: any) =>
          lens.command?.title === "$(play) Run All Tests" ||
          lens.command?.title === "$(debug) Debug All Tests",
      );
      assert.ok(classLens, "Should have class-level CodeLens");
    });

    it("should return CodeLens for each Spock test method", async () => {
      documentMock.getText.returns(
        "package com.example\n" +
          "import spock.lang.Specification\n" +
          "\n" +
          "class MySpec extends Specification {\n" +
          '  def "test one"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          '  def "test two"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      // Should have at least 6 CodeLenses: 3 for class (Run All, Debug All, Coverage) + 3*2 for methods (Run, Debug, Coverage each)
      assert.ok(
        codeLenses.length >= 6,
        `Expected at least 6 CodeLenses, got ${codeLenses.length}`,
      );

      // Check for "Run" CodeLenses (with codicon)
      const runTestLenses = codeLenses.filter(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      assert.ok(
        runTestLenses.length >= 2,
        `Expected at least 2 "$(play) Run" CodeLenses, got ${runTestLenses.length}`,
      );
    });

    it("should return CodeLens for JUnit @Test methods", async () => {
      documentMock.getText.returns(
        "package com.example\n" +
          "import org.junit.Test\n" +
          "\n" +
          "class MyTest {\n" +
          "  @Test\n" +
          "  void testSomething() {\n" +
          "    // test code\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      // Should have CodeLenses for the test method
      assert.ok(
        codeLenses.length >= 2,
        `Expected at least 2 CodeLenses, got ${codeLenses.length}`,
      );

      const runTestLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      assert.ok(runTestLens, 'Should have "$(play) Run" CodeLens for JUnit test');
    });

    it("should have correct command IDs for test methods", async () => {
      documentMock.getText.returns(
        "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          '  def "test method"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      const runLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      const debugLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(debug) Debug",
      );

      assert.ok(runLens, "Should have $(play) Run CodeLens");
      assert.ok(debugLens, "Should have $(debug) Debug CodeLens");

      assert.strictEqual(runLens.command.command, "groovy.test.run");
      assert.strictEqual(debugLens.command.command, "groovy.test.debug");
    });

    it("should pass correct arguments to test commands", async () => {
      documentMock.getText.returns(
        "package com.example\n" +
          "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          '  def "test method"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      const runLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );

      assert.ok(runLens, "Should have $(play) Run CodeLens");
      assert.ok(runLens.command.arguments, "Command should have arguments");
      assert.strictEqual(
        runLens.command.arguments.length,
        1,
        "Should have one argument object",
      );

      const args = runLens.command.arguments[0];
      assert.ok(args.uri, "Arguments should include uri");
      assert.ok(args.suite, "Arguments should include suite (class name)");
      assert.ok(args.test, "Arguments should include test (method name)");

      assert.strictEqual(
        args.suite,
        "com.example.MySpec",
        "Suite should be fully qualified class name",
      );
      assert.strictEqual(
        args.test,
        "test method",
        "Test should be the method name",
      );
    });

    it("should handle single-quoted Spock test names", async () => {
      documentMock.getText.returns(
        "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          "  def 'test with single quotes'() {\n" +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      const runLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      assert.ok(runLens, "Should have CodeLens for single-quoted test name");

      const args = runLens.command.arguments[0];
      assert.strictEqual(args.test, "test with single quotes");
    });

    it("should work without TestService (offline mode)", async () => {
      documentMock.getText.returns(
        "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          '  def "test method"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      // Create provider without TestService
      provider = new TestCodeLensProvider(undefined);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.ok(
        codeLenses.length >= 2,
        "Should work without TestService (offline mode)",
      );
    });

    // Bug #1: Multiple class detection
    it("should not show CodeLens on utility class in file with test class", async () => {
      documentMock.getText.returns(
        "class UtilityClass {\n" +
          "    def helper() {}\n" +
          "}\n" +
          "\n" +
          "class MyTest {\n" +
          "    @Test\n" +
          "    void testSomething() {}\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      // Should have CodeLenses (class is detected as test class because of @Test)
      // But the CodeLens should be at line 4 (MyTest class), not line 0 (UtilityClass)
      assert.ok(codeLenses.length > 0, "Should have CodeLenses for test class");

      // Check that CodeLens is on MyTest class line (line 4), not UtilityClass (line 0)
      const classLens = codeLenses.find(
        (lens: any) =>
          lens.command?.title === "$(play) Run All Tests" ||
          lens.command?.title === "$(debug) Debug All Tests",
      );
      assert.ok(classLens, "Should have class-level CodeLens");
      assert.strictEqual(
        classLens.range.start.line,
        4,
        "CodeLens should be on MyTest class line, not UtilityClass",
      );
    });

    // Bug #2: Incomplete @Test pattern
    it("should detect @Test with parameters", async () => {
      documentMock.getText.returns(
        "import org.junit.Test\n" +
          "\n" +
          "class MyTest {\n" +
          "    @Test(timeout = 1000)\n" +
          "    void testWithTimeout() {}\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.ok(
        codeLenses.length >= 2,
        "Should have CodeLenses for test with parameters",
      );
      const runTestLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      assert.ok(runTestLens, "Should detect @Test with parameters");
    });

    it("should detect @Test on same line as method", async () => {
      documentMock.getText.returns(
        "import org.junit.Test\n" +
          "\n" +
          "class MyTest {\n" +
          "    @Test void inlineTest() {}\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.ok(
        codeLenses.length >= 2,
        "Should have CodeLenses for inline test",
      );
      const runTestLens = codeLenses.find(
        (lens: any) => lens.command?.title === "$(play) Run",
      );
      assert.ok(runTestLens, "Should detect @Test on same line as method");
    });

    // Bug #3: False positives from comments/strings
    it("should not detect @Test in comments", async () => {
      documentMock.getText.returns(
        "class MyClass {\n" +
          "    // TODO: Add @Test later\n" +
          "    def notATest() {}\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.strictEqual(
        codeLenses.length,
        0,
        "Should not detect @Test in comments",
      );
    });

    it("should not detect @Test in strings", async () => {
      documentMock.getText.returns(
        "class MyClass {\n" +
          '    def str = "@Test annotation"\n' +
          "    def method() {}\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.strictEqual(
        codeLenses.length,
        0,
        "Should not detect @Test in strings",
      );
    });

    // Bug #4: Configuration setting
    it("should respect codelens.test.enabled config setting when disabled", async () => {
      // Mock workspace configuration
      const configMock = {
        get: sandbox
          .stub()
          .withArgs("codelens.test.enabled", true)
          .returns(false),
      };
      vscodeMock.workspace = {
        getConfiguration: sandbox.stub().withArgs("groovy").returns(configMock),
      };

      // Re-create provider with updated vscode mock
      const module = (proxyquire as any).noCallThru()(
        "../../../../src/features/testing/TestCodeLensProvider",
        {
          vscode: vscodeMock,
        },
      );
      TestCodeLensProvider = module.TestCodeLensProvider;

      documentMock.getText.returns(
        "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          '  def "test method"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.strictEqual(
        codeLenses.length,
        0,
        "Should return no CodeLenses when setting is disabled",
      );
    });

    it("should show CodeLens when codelens.test.enabled config setting is enabled", async () => {
      // Mock workspace configuration
      const configMock = {
        get: sandbox
          .stub()
          .withArgs("codelens.test.enabled", true)
          .returns(true),
      };
      vscodeMock.workspace = {
        getConfiguration: sandbox.stub().withArgs("groovy").returns(configMock),
      };

      // Re-create provider with updated vscode mock
      const module = (proxyquire as any).noCallThru()(
        "../../../../src/features/testing/TestCodeLensProvider",
        {
          vscode: vscodeMock,
        },
      );
      TestCodeLensProvider = module.TestCodeLensProvider;

      documentMock.getText.returns(
        "import spock.lang.Specification\n" +
          "class MySpec extends Specification {\n" +
          '  def "test method"() {\n' +
          "    expect: true\n" +
          "  }\n" +
          "}",
      );

      provider = new TestCodeLensProvider(testServiceMock);
      const codeLenses = await provider.provideCodeLenses(documentMock);

      assert.ok(
        codeLenses.length > 0,
        "Should return CodeLenses when setting is enabled",
      );
    });
  });
});
