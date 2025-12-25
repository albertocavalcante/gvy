import { expect } from 'chai';
import proxyquire = require('proxyquire');
import { vscode } from '../../mocks/vscode';

// Import the type separately for static analysis
import type { ReplStatusBarLogic as ReplStatusBarLogicType } from '../../../../src/features/repl/ReplStatusBar';

const { ReplStatusBarLogic } = proxyquire.noCallThru()('../../../../src/features/repl/ReplStatusBar', {
    'vscode': vscode
});

describe('ReplStatusBarLogic', () => {
    let statusBarLogic: ReplStatusBarLogicType;

    beforeEach(() => {
        statusBarLogic = new ReplStatusBarLogic();
    });

    it('should return correct state when REPL is running', () => {
        const state = statusBarLogic.getState(true); // isRunning = true
        expect(state.text).to.equal('$(terminal) REPL');
        expect(state.tooltip).to.include('Groovy REPL is running');
        expect(state.command).to.equal('groovy.repl.show');
        expect(state.color?.id).to.equal('statusBarItem.activeBackground');
    });

    it('should return correct state when REPL is stopped', () => {
        const state = statusBarLogic.getState(false); // isRunning = false
        expect(state.text).to.equal('$(terminal) REPL');
        expect(state.tooltip).to.include('Groovy REPL is not running');
        expect(state.command).to.equal('groovy.repl.start');
        expect(state.color).to.be.undefined;
    });
});
