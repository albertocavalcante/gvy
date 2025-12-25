
import * as vscode from 'vscode';
import { ILSPToolService, AISymbolInfo, AILocation } from './types';

/**
 * Manages the registration and availability of AI tools based on configuration.
 */
export class ToolRegistry {
    constructor(
        private readonly config: vscode.WorkspaceConfiguration
    ) { }

    /**
     * Checks if a specific tool is enabled.
     */
    public isToolEnabled(toolName: string): boolean {
        // 1. Check Master Switch
        const enabled = this.config.get<boolean>('ai.tools.enabled', false);
        if (!enabled) {
            return false;
        }

        // 2. Check Allowed List
        const allowed = this.config.get<string[]>('ai.tools.allowed', ['all']);
        if (!allowed) {
            return false;
        }

        if (allowed.includes('all')) {
            return true;
        }

        return allowed.includes(toolName);
    }

}
