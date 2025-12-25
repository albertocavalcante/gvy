/**
 * Type definitions for AI Tool Services
 */

import * as vscode from 'vscode';

/**
 * Represents a simplified symbol information for LLM consumption
 */
export interface AISymbolInfo {
    name: string;
    kind: string;
    containerName: string;
    location: {
        uri: string;
        range: {
            start: { line: number; character: number };
            end: { line: number; character: number };
        };
    };
}

/**
 * Represents a simplified location for LLM consumption
 */
export interface AILocation {
    uri: string;
    range: {
        start: { line: number; character: number };
        end: { line: number; character: number };
    };
}

/**
 * Interface for the core LSP Tool Service
 */
export interface ILSPToolService {
    /**
     * Finds a symbol in the workspace by name query
     */
    findWorkspaceSymbol(query: string): Promise<AISymbolInfo[]>;

    /**
     * Finds references to the symbol at the given location
     */
    findReferences(uri: string, line: number, character: number): Promise<AILocation[]>;

    /**
     * Finds the definition of the symbol at the given location
     */
    getDefinition(uri: string, line: number, character: number): Promise<AILocation | null>;
}

/**
 * Definition of an AI Tool to be shared between LM and Command providers.
 */
export interface ToolDefinition {
    name: string;
    command: string;
    handler: (service: ILSPToolService, params: any) => Promise<any>;
}

export const TOOL_DEFINITIONS: ToolDefinition[] = [
    {
        name: 'groovy_find_symbol',
        command: 'groovy.ai.find_symbol',
        handler: async (service, params) => {
            if (typeof params?.query !== 'string') {
                throw new Error("Parameter 'query' is required for groovy_find_symbol.");
            }
            return service.findWorkspaceSymbol(params.query);
        }
    },
    {
        name: 'groovy_get_references',
        command: 'groovy.ai.get_references',
        handler: async (service, params) => {
            if (typeof params?.uri !== 'string' || typeof params?.line !== 'number' || typeof params?.character !== 'number') {
                throw new Error("Parameters 'uri', 'line', and 'character' are required for groovy_get_references.");
            }
            return service.findReferences(params.uri, params.line, params.character);
        }
    },
    {
        name: 'groovy_get_definition',
        command: 'groovy.ai.get_definition',
        handler: async (service, params) => {
            if (typeof params?.uri !== 'string' || typeof params?.line !== 'number' || typeof params?.character !== 'number') {
                throw new Error("Parameters 'uri', 'line', and 'character' are required for groovy_get_definition.");
            }
            return service.getDefinition(params.uri, params.line, params.character);
        }
    }
];
