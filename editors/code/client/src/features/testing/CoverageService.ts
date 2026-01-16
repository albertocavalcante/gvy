import * as vscode from "vscode";
import { TestService, FileCoverageData } from "./TestService";

/**
 * Provides coverage data to VS Code by delegating to the Groovy LSP.
 *
 * The LSP handles JaCoCo XML parsing (supporting multi-module projects),
 * while this service translates the results into VS Code's coverage API.
 */
export class CoverageService {
  constructor(
    private readonly testService: TestService,
    private readonly logger: vscode.OutputChannel,
  ) {}

  /**
   * Fetch coverage from LSP and add to the TestRun.
   */
  public async addCoverageToRun(
    run: vscode.TestRun,
    workspaceUri: string,
  ): Promise<void> {
    try {
      this.logger.appendLine("[COVERAGE] Fetching coverage from LSP...");

      const response = await this.testService.getCoverage(workspaceUri);

      if (response.files.length === 0) {
        this.logger.appendLine("[COVERAGE] No coverage data found");
        return;
      }

      this.logger.appendLine(
        `[COVERAGE] LSP returned coverage for ${response.files.length} files`,
      );

      // Log summary
      const summary = response.summary;
      this.logger.appendLine(
        `[COVERAGE] Summary: ${summary.lineCoveragePercent.toFixed(1)}% lines ` +
          `(${summary.linesCovered}/${summary.linesTotal}), ` +
          `${summary.branchCoveragePercent.toFixed(1)}% branches ` +
          `(${summary.branchesCovered}/${summary.branchesTotal})`,
      );

      // Add coverage for each file
      for (const fileData of response.files) {
        try {
          const coverage = this.createFileCoverage(fileData);
          run.addCoverage(coverage);
        } catch (error) {
          this.logger.appendLine(
            `[COVERAGE] Error creating coverage for ${fileData.uri}: ${error}`,
          );
        }
      }
    } catch (error) {
      this.logger.appendLine(`[COVERAGE] Error fetching coverage: ${error}`);
    }
  }

  /**
   * Create a VS Code FileCoverage object from LSP data.
   */
  private createFileCoverage(data: FileCoverageData): vscode.FileCoverage {
    const uri = vscode.Uri.parse(data.uri);

    const detailedCoverage: vscode.StatementCoverage[] = data.lines.map(
      (line) => {
        const position = new vscode.Position(line.line - 1, 0);

        // Create branch coverage if branches exist
        const branches: vscode.BranchCoverage[] = [];
        if (line.branchInfo) {
          const { covered, total } = line.branchInfo;
          const missed = total - covered;

          // Add covered branches
          for (let i = 0; i < covered; i++) {
            branches.push(new vscode.BranchCoverage(1, position));
          }
          // Add missed branches
          for (let i = 0; i < missed; i++) {
            branches.push(new vscode.BranchCoverage(0, position));
          }
        }

        // Use hitCount if available, otherwise 1 for covered, 0 for not
        const hitCount = line.hitCount ?? (line.covered ? 1 : 0);

        return new vscode.StatementCoverage(
          hitCount,
          position,
          branches.length > 0 ? branches : undefined,
        );
      },
    );

    return vscode.FileCoverage.fromDetails(uri, detailedCoverage);
  }
}
