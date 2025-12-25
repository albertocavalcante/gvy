import type * as vscodeType from 'vscode';
import type { LanguageClient } from 'vscode-languageclient/node';
import { AISymbolInfo, AILocation, ILSPToolService } from './types';

export class LSPToolService implements ILSPToolService {

    constructor(
        private readonly vscode: typeof vscodeType,
        private readonly getClient: () => LanguageClient | undefined
    ) { }


    /**
     * Finds symbols in the workspace matching the query.
     */
    public async findWorkspaceSymbol(query: string): Promise<AISymbolInfo[]> {
        // Use VS Code's commands which route to the LSP
        const symbols = await this.vscode.commands.executeCommand<vscodeType.SymbolInformation[]>(
            'vscode.executeWorkspaceSymbolProvider',
            query
        );

        return symbols.map((s: vscodeType.SymbolInformation) => this.toAISymbolInfo(s));
    }

    /**
     * Finds references to the symbol at the specified position.
     */
    public async findReferences(uriStr: string, line: number, character: number): Promise<AILocation[]> {
        const uri = this.vscode.Uri.parse(uriStr);
        const position = new this.vscode.Position(line, character);

        const locations = await this.vscode.commands.executeCommand<vscodeType.Location[]>(
            'vscode.executeReferenceProvider',
            uri,
            position
        );

        return locations.map((l: vscodeType.Location) => this.toAILocation(l));
    }

    /**
    * Finds the definition of the symbol at the specified position.
    */
    public async getDefinition(uriStr: string, line: number, character: number): Promise<AILocation | null> {
        const uri = this.vscode.Uri.parse(uriStr);
        const position = new this.vscode.Position(line, character);

        const result = await this.vscode.commands.executeCommand<vscodeType.Location | vscodeType.Location[] | vscodeType.LocationLink[]>(
            'vscode.executeDefinitionProvider',
            uri,
            position
        );

        if (!result) {
            return null;
        }

        if (Array.isArray(result)) {
            if (result.length === 0) {
                return null;
            }
            // If it's Location[]
            if ('uri' in result[0]) {
                return this.toAILocation(result[0] as vscodeType.Location);
            }
            // If it's LocationLink[]
            return this.linkToAILocation(result[0] as vscodeType.LocationLink);
        }

        // Single location
        return this.toAILocation(result as vscodeType.Location);
    }

    // --- Helpers ---

    private toAISymbolInfo(symbol: vscodeType.SymbolInformation): AISymbolInfo {
        return {
            name: symbol.name,
            kind: this.vscode.SymbolKind[symbol.kind],
            containerName: symbol.containerName,
            location: {
                uri: symbol.location.uri.toString(),
                range: {
                    start: { line: symbol.location.range.start.line, character: symbol.location.range.start.character },
                    end: { line: symbol.location.range.end.line, character: symbol.location.range.end.character }
                }
            }
        };
    }

    private toAILocation(loc: vscodeType.Location): AILocation {
        return {
            uri: loc.uri.toString(),
            range: {
                start: { line: loc.range.start.line, character: loc.range.start.character },
                end: { line: loc.range.end.line, character: loc.range.end.character }
            }
        };
    }

    private linkToAILocation(link: vscodeType.LocationLink): AILocation {
        return {
            uri: link.targetUri.toString(),
            range: {
                start: { line: link.targetRange.start.line, character: link.targetRange.start.character },
                end: { line: link.targetRange.end.line, character: link.targetRange.end.character }
            }
        };
    }
}
