import { expect } from 'chai';
import * as sinon from 'sinon';
import proxyquire = require('proxyquire');
import { vscode, mockTasks } from '../../mocks/vscode';

import type { GroovyTestRunner as GroovyTestRunnerType } from '../../../../src/features/testing/TestRunner';

// Use a more robust proxyquire syntax to ensure mocks are loaded correctly.
const { GroovyTestRunner } = proxyquire.noCallThru()('../../../../src/features/testing/TestRunner', {
    'vscode': vscode
});

describe('GroovyTestRunner', () => {
    let runner: GroovyTestRunnerType;

    beforeEach(() => {
        mockTasks.executeTask.resetHistory();
        runner = new GroovyTestRunner();
    });

    afterEach(() => {
        sinon.restore();
    });

    describe('constructTestFilter', () => {
        it('should create a filter for a single test method', () => {
            const testItem = {
                id: 'file:///path/to/MySpec.groovy?test=MySpec.should do something',
                parent: {
                    id: 'file:///path/to/MySpec.groovy?suite=MySpec',
                }
            };
            const filter = runner.constructTestFilter([testItem as any]);
            expect(filter).to.equal('--tests "MySpec.should do something"');
        });

        it('should create a filter for a whole test suite', () => {
            const testItem = {
                id: 'file:///path/to/MySpec.groovy?suite=MySpec',
            };
            const filter = runner.constructTestFilter([testItem as any]);
            expect(filter).to.equal('--tests "MySpec"');
        });
    });

    describe('getTestNameFromId', () => {
        it('should extract test name from a method ID', () => {
            const fullName = runner.getTestNameFromId('file:///path/to/A.groovy?test=com.pkg.A.test name');
            expect(fullName).to.equal('com.pkg.A.test name');
        });

        it('should extract suite name from a suite ID', () => {
            const suiteName = runner.getTestNameFromId('file:///path/to/B.groovy?suite=com.pkg.B');
            expect(suiteName).to.equal('com.pkg.B');
        });
    });

    describe('run', () => {
        it('should create and execute a shell task for running tests', async () => {
            const workspaceFolder = { uri: { fsPath: '/path/to/workspace' } };
            const testItems = [{
                id: 'file:///path/to/MySpec.groovy?suite=MySpec',
            }];

            await runner.run(workspaceFolder as any, testItems as any[]);
            
            expect(mockTasks.executeTask.calledOnce).to.be.true;
            const executedTask = mockTasks.executeTask.firstCall.args[0];
            expect(executedTask.name).to.equal('Run Groovy Tests');
            expect(executedTask.source).to.equal('Groovy');
            const shellExec = executedTask.execution;
            expect(shellExec.commandLine).to.equal('gradle test --tests "MySpec"');
        });
    });
});
