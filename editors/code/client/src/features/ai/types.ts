/**
 * Type definitions for AI Tool Services
 */

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
  findReferences(
    uri: string,
    line: number,
    character: number,
  ): Promise<AILocation[]>;

  /**
   * Finds the definition of the symbol at the given location
   */
  getDefinition(
    uri: string,
    line: number,
    character: number,
  ): Promise<AILocation | null>;
}

/**
 * Parameter interfaces for AI tools
 */
export interface FindSymbolParams {
  query: string;
}

export interface LocationParams {
  uri: string;
  line: number;
  character: number;
}

/**
 * Definition of an AI Tool to be shared between LM and Command providers.
 */
export interface ToolDefinition {
  name: string;
  command: string;
  handler: (service: ILSPToolService, params: unknown) => Promise<unknown>;
}

function readLocation(
  params: unknown,
  tool: string,
): { uri: string; line: number; character: number } {
  const p = (params ?? {}) as {
    uri?: unknown;
    line?: unknown;
    character?: unknown;
  };
  if (
    typeof p.uri !== "string" ||
    p.uri.length === 0 ||
    typeof p.line !== "number" ||
    typeof p.character !== "number"
  ) {
    throw new Error(
      `Parameters 'uri', 'line', and 'character' are required for ${tool}.`,
    );
  }
  const isPosition = (n: number) => Number.isInteger(n) && n >= 0;
  if (!isPosition(p.line) || !isPosition(p.character)) {
    throw new Error(
      `Parameters 'line' and 'character' must be non-negative integers (0-indexed) for ${tool}.`,
    );
  }
  return { uri: p.uri, line: p.line, character: p.character };
}

export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "groovy_find_symbol",
    command: "groovy.ai.find_symbol",
    // Expected params: FindSymbolParams
    handler: async (service, params) => {
      const p = (params ?? {}) as { query?: unknown };
      if (typeof p.query !== "string") {
        throw new Error(
          "Parameter 'query' is required for groovy_find_symbol.",
        );
      }
      return service.findWorkspaceSymbol(p.query);
    },
  },
  {
    name: "groovy_get_references",
    command: "groovy.ai.get_references",
    // Expected params: LocationParams
    handler: async (service, params) => {
      const { uri, line, character } = readLocation(
        params,
        "groovy_get_references",
      );
      return service.findReferences(uri, line, character);
    },
  },
  {
    name: "groovy_get_definition",
    command: "groovy.ai.get_definition",
    // Expected params: LocationParams
    handler: async (service, params) => {
      const { uri, line, character } = readLocation(
        params,
        "groovy_get_definition",
      );
      return service.getDefinition(uri, line, character);
    },
  },
];
