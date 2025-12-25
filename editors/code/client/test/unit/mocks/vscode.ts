import * as sinon from 'sinon';

export const mockTasks = {
    executeTask: sinon.stub()
};

export class MockShellExecution {
    public commandLine: string;
    constructor(commandLine: string) {
        this.commandLine = commandLine;
    }
}

export class MockTask {
    public definition: any;
    public scope: any;
    public name: string;
    public source: string;
    public execution: any;

    constructor(definition: any, scope: any, name: string, source: string, execution: any) {
        this.definition = definition;
        this.scope = scope;
        this.name = name;
        this.source = source;
        this.execution = execution;
    }
}

export class MockThemeColor {
    constructor(public id: string) {}
}

export const vscode = {
    tasks: mockTasks,
    Task: MockTask,
    ShellExecution: MockShellExecution,
    ThemeColor: MockThemeColor,
    // Stubs for other vscode APIs can be added here
    window: {
        createStatusBarItem: sinon.stub().returns({
            show: sinon.stub(),
            dispose: sinon.stub()
        }),
        showInformationMessage: sinon.stub(),
        showErrorMessage: sinon.stub()
    },
    workspace: {
        getConfiguration: sinon.stub()
    },
    commands: {
        executeCommand: sinon.stub()
    },
    env: {
        openExternal: sinon.stub()
    },
    Uri: {
        parse: sinon.stub().callsFake((url: string) => ({ toString: () => url }))
    },
    StatusBarAlignment: {
        Right: 2
    },
    ConfigurationTarget: {
        Global: 1,
        Workspace: 2,
        WorkspaceFolder: 3
    }
};
