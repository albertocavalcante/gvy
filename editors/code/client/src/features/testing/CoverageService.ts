import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';

/**
 * JaCoCo XML line coverage data.
 * Attributes: nr (line number), mi (missed instructions), ci (covered instructions),
 * mb (missed branches), cb (covered branches)
 */
interface JacocoLine {
    nr: number;
    mi: number;
    ci: number;
    mb: number;
    cb: number;
}

/**
 * Coverage data for a single source file.
 */
interface FileCoverageData {
    uri: vscode.Uri;
    lines: JacocoLine[];
}

/**
 * Parses JaCoCo XML reports and provides coverage data to VS Code.
 *
 * TODO: Consider offloading JaCoCo parsing to the Groovy Language Server (LSP)
 * via a custom `groovy/getCoverage` method. This would make coverage data
 * available to other editors (Neovim, Sublime, etc.) and centralize the logic.
 */
export class CoverageService {
    constructor(private readonly logger: vscode.OutputChannel) { }

    /**
     * Parse JaCoCo XML report and add coverage to the TestRun.
     */
    public async addCoverageToRun(
        run: vscode.TestRun,
        workspacePath: string,
    ): Promise<void> {
        const reportPath = this.findJacocoReport(workspacePath);
        if (!reportPath) {
            this.logger.appendLine('[COVERAGE] JaCoCo report not found');
            return;
        }

        this.logger.appendLine(`[COVERAGE] Parsing: ${reportPath}`);

        try {
            const coverageData = await this.parseJacocoXml(reportPath, workspacePath);

            for (const fileCoverage of coverageData) {
                const coverage = this.createFileCoverage(fileCoverage);
                run.addCoverage(coverage);
            }

            this.logger.appendLine(
                `[COVERAGE] Added coverage for ${coverageData.length} files`,
            );
        } catch (error) {
            this.logger.appendLine(`[COVERAGE] Error parsing report: ${error}`);
        }
    }

    /**
     * Find the JaCoCo XML report in common locations.
     */
    private findJacocoReport(workspacePath: string): string | undefined {
        const commonPaths = [
            'build/reports/jacoco/test/jacocoTestReport.xml',
            'build/reports/jacoco/jacocoTestReport.xml',
            'target/site/jacoco/jacoco.xml', // Maven
        ];

        for (const relPath of commonPaths) {
            const fullPath = path.join(workspacePath, relPath);
            if (fs.existsSync(fullPath)) {
                return fullPath;
            }
        }

        return undefined;
    }

    /**
     * Parse JaCoCo XML and extract per-file line coverage.
     */
    private async parseJacocoXml(
        reportPath: string,
        workspacePath: string,
    ): Promise<FileCoverageData[]> {
        const xml = await fs.promises.readFile(reportPath, 'utf-8');
        const coverageData: FileCoverageData[] = [];

        // Simple regex-based parsing for JaCoCo XML
        // Match: <package name="com/example">
        const packageRegex = /<package\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/package>/g;
        // Match: <sourcefile name="MyClass.groovy">
        const sourcefileRegex =
            /<sourcefile\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/sourcefile>/g;
        // Match: <line nr="10" mi="0" ci="5" mb="0" cb="2"/>
        const lineRegex =
            /<line\s+nr="(\d+)"\s+mi="(\d+)"\s+ci="(\d+)"(?:\s+mb="(\d+)")?(?:\s+cb="(\d+)")?/g;

        let packageMatch;
        while ((packageMatch = packageRegex.exec(xml)) !== null) {
            const packageName = packageMatch[1].replace(/\//g, path.sep);
            const packageContent = packageMatch[2];

            let sourceMatch;
            while ((sourceMatch = sourcefileRegex.exec(packageContent)) !== null) {
                const filename = sourceMatch[1];
                const sourceContent = sourceMatch[2];

                // Try to find the source file in the workspace
                const possiblePaths = [
                    path.join(workspacePath, 'src', 'main', 'groovy', packageName, filename),
                    path.join(workspacePath, 'src', 'test', 'groovy', packageName, filename),
                    path.join(workspacePath, 'src', packageName, filename),
                ];

                let fileUri: vscode.Uri | undefined;
                for (const p of possiblePaths) {
                    if (fs.existsSync(p)) {
                        fileUri = vscode.Uri.file(p);
                        break;
                    }
                }

                if (!fileUri) {
                    continue; // Skip files we can't locate
                }

                const lines: JacocoLine[] = [];
                let lineMatch;
                while ((lineMatch = lineRegex.exec(sourceContent)) !== null) {
                    lines.push({
                        nr: parseInt(lineMatch[1], 10),
                        mi: parseInt(lineMatch[2], 10),
                        ci: parseInt(lineMatch[3], 10),
                        mb: parseInt(lineMatch[4] || '0', 10),
                        cb: parseInt(lineMatch[5] || '0', 10),
                    });
                }

                if (lines.length > 0) {
                    coverageData.push({ uri: fileUri, lines });
                }
            }
        }

        return coverageData;
    }

    /**
     * Create a VS Code FileCoverage object from parsed data.
     */
    private createFileCoverage(data: FileCoverageData): vscode.FileCoverage {
        const detailedCoverage: vscode.StatementCoverage[] = data.lines.map(
            (line) => {
                const position = new vscode.Position(line.nr - 1, 0);
                const covered = line.ci > 0;

                // Create branch coverage if branches exist
                const branches: vscode.BranchCoverage[] = [];
                if (line.mb > 0 || line.cb > 0) {
                    branches.push(
                        new vscode.BranchCoverage(line.cb > 0, position, 'branch'),
                    );
                }

                return new vscode.StatementCoverage(
                    covered ? 1 : 0,
                    position,
                    branches.length > 0 ? branches : undefined,
                );
            },
        );

        return vscode.FileCoverage.fromDetails(
            data.uri,
            detailedCoverage,
        );
    }
}
