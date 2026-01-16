import * as vscode from "vscode";
import { DependencyService, DependencyInfo } from "./DependencyService";

/**
 * Tree item representing either a scope group or a dependency
 */
export class DependencyItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly contextValue: string,
    public readonly coordinate?: string,
    public readonly isTransitive?: boolean,
    public readonly children?: DependencyItem[],
  ) {
    super(label, collapsibleState);

    if (contextValue === "scope") {
      this.iconPath = new vscode.ThemeIcon("package");
    } else if (contextValue === "dependency") {
      this.iconPath = new vscode.ThemeIcon("file-zip");
      this.tooltip = coordinate;
      // Dim transitive dependencies
      if (isTransitive) {
        this.description = "(transitive)";
      }
    }
  }
}

/**
 * Provides tree data for the Groovy Dependencies view
 */
export class DependencyTreeProvider
  implements vscode.TreeDataProvider<DependencyItem>
{
  private static readonly SCOPE_ORDER = ["compile", "runtime", "test", "provided"];

  private _onDidChangeTreeData: vscode.EventEmitter<
    DependencyItem | undefined | null | void
  > = new vscode.EventEmitter<DependencyItem | undefined | null | void>();

  readonly onDidChangeTreeData: vscode.Event<
    DependencyItem | undefined | null | void
  > = this._onDidChangeTreeData.event;

  private dependenciesCache: Map<string, DependencyItem[]> = new Map();

  constructor(private readonly dependencyService: DependencyService) {}

  /**
   * Refresh the tree view
   */
  refresh(): void {
    this.dependenciesCache.clear();
    this._onDidChangeTreeData.fire();
  }

  /**
   * Get tree item for display
   */
  getTreeItem(element: DependencyItem): vscode.TreeItem {
    return element;
  }

  /**
   * Get children for a tree item
   */
  async getChildren(element?: DependencyItem): Promise<DependencyItem[]> {
    if (!element) {
      // Root level - show scope groups for each workspace
      return this.getRootItems();
    }

    // Return cached children if available
    if (element.children) {
      return element.children;
    }

    return [];
  }

  /**
   * Get root level items (scope groups for all workspaces)
   */
  private async getRootItems(): Promise<DependencyItem[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      return [];
    }

    const allItems: DependencyItem[] = [];

    for (const workspaceFolder of workspaceFolders) {
      const cacheKey = workspaceFolder.uri.toString();

      // Check cache first
      if (this.dependenciesCache.has(cacheKey)) {
        const cached = this.dependenciesCache.get(cacheKey);
        if (cached) {
          allItems.push(...cached);
          continue;
        }
      }

      try {
        const result = await this.dependencyService.getDependencies(
          workspaceFolder.uri.toString(),
        );

        if (!result || !result.dependencies || result.dependencies.length === 0) {
          // No dependencies found for this workspace - this is expected for non-Groovy projects
          // The LSP server returns empty dependencies with buildTool="unknown" for such cases
          console.debug(`No dependencies found for ${workspaceFolder.name}`);
          continue;
        }

        // Group dependencies by scope
        const scopeMap = this.groupByScope(result.dependencies);
        const scopeItems = this.createScopeItems(scopeMap, workspaceFolders.length > 1 ? workspaceFolder.name : undefined);

        // Cache the result
        this.dependenciesCache.set(cacheKey, scopeItems);
        allItems.push(...scopeItems);
      } catch (error) {
        // Log the error for debugging but don't show popup for expected scenarios
        console.error(
          `Failed to fetch dependencies for ${workspaceFolder.name}:`,
          error,
        );
        // Only show popup for genuine errors (not network timeouts or missing projects)
        if (error instanceof Error &&
            !error.message.includes("timeout") &&
            !error.message.includes("not found") &&
            !error.message.includes("No build tool")) {
          vscode.window.showErrorMessage(
            `Failed to fetch dependencies for ${workspaceFolder.name}: ${error.message}`,
          );
        }
      }
    }

    return allItems;
  }

  /**
   * Group dependencies by scope
   */
  private groupByScope(
    dependencies: DependencyInfo[],
  ): Map<string, DependencyInfo[]> {
    const scopeMap = new Map<string, DependencyInfo[]>();

    for (const dep of dependencies) {
      const scope = dep.scope || "runtime";
      if (!scopeMap.has(scope)) {
        scopeMap.set(scope, []);
      }
      scopeMap.get(scope)!.push(dep);
    }

    return scopeMap;
  }

  /**
   * Create tree items for each scope group
   */
  private createScopeItems(
    scopeMap: Map<string, DependencyInfo[]>,
    workspacePrefix?: string,
  ): DependencyItem[] {
    const scopeItems: DependencyItem[] = [];

    // Sort scopes in a logical order
    const sortedScopes = Array.from(scopeMap.keys()).sort((a, b) => {
      const aIndex = DependencyTreeProvider.SCOPE_ORDER.indexOf(a);
      const bIndex = DependencyTreeProvider.SCOPE_ORDER.indexOf(b);
      if (aIndex === -1 && bIndex === -1) return a.localeCompare(b);
      if (aIndex === -1) return 1;
      if (bIndex === -1) return -1;
      return aIndex - bIndex;
    });

    for (const scope of sortedScopes) {
      const deps = scopeMap.get(scope)!;
      const children = this.createDependencyItems(deps);

      const label = workspacePrefix
        ? `${workspacePrefix} - ${scope} (${deps.length})`
        : `${scope} (${deps.length})`;

      const scopeItem = new DependencyItem(
        label,
        vscode.TreeItemCollapsibleState.Collapsed,
        "scope",
        undefined,
        undefined,
        children,
      );

      scopeItems.push(scopeItem);
    }

    return scopeItems;
  }

  /**
   * Create tree items for dependencies
   */
  private createDependencyItems(
    dependencies: DependencyInfo[],
  ): DependencyItem[] {
    return dependencies.map((dep) => {
      const coordinate = `${dep.name}:${dep.version}`;
      return new DependencyItem(
        coordinate,
        vscode.TreeItemCollapsibleState.None,
        "dependency",
        coordinate,
        dep.isTransitive,
      );
    });
  }
}
