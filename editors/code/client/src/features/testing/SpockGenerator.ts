// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as path from "path";

export interface MethodInfo {
  name: string;
}

/** True when `target` is `root` itself or lexically inside it. */
export function isInsideDirectory(root: string, target: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return (
    relative !== ".." &&
    !relative.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(relative)
  );
}

export class SpockGenerator {
  /**
   * Generates the content for a Spock Specification file.
   */
  public generateSpec(
    className: string,
    methods: MethodInfo[],
    packageName: string,
  ): string {
    // TODO: Make suffix configurable (e.g. 'Spec', 'SpockTest', 'Test')
    const specName = `${className}Spec`;

    const methodTests = methods
      .map((m) => {
        return `    def "should test ${m.name}"() {
        given:
        // TODO: Mock dependencies
        def subject = new ${className}()

        when:
        // subject.${m.name}()

        then:
        // verify results
        true
    }`;
      })
      .join("\n\n");

    const packageDecl = packageName ? `package ${packageName}\n\n` : "";

    return `${packageDecl}import spock.lang.Specification

class ${specName} extends Specification {

    def "${className} should instantiate"() {
        expect:
        new ${className}() != null
    }

${methodTests}
}
`;
  }

  /**
   * Heuristically resolves the target test file path.
   * Returns the absolute file path as a string.
   *
   * @todo PROD-READY: Replace heuristic with project-model query (e.g. Gradle model).
   * Current logic assumes standard 'src/main/groovy' -> 'src/test/groovy' layout.
   */
  public resolveTestPath(sourcePath: string, workspaceRoot?: string): string {
    const dir = path.dirname(sourcePath);
    const testFileName = path
      .basename(sourcePath)
      .replace(/\.groovy$/, "Spec.groovy");

    // Standard layout: swap the last `src/main` for `src/test`.
    const mainSegment = `${path.sep}src${path.sep}main${path.sep}`;
    const at = `${dir}${path.sep}`.lastIndexOf(mainSegment);
    if (at >= 0) {
      const testDir =
        `${dir}${path.sep}`.slice(0, at) +
        `${path.sep}src${path.sep}test${path.sep}` +
        `${dir}${path.sep}`.slice(at + mainSegment.length);
      return path.join(testDir, testFileName);
    }

    // Fallback for non-standard layouts: a sibling 'test' folder, unless that
    // would leave the workspace (e.g. a file at the workspace root); then use
    // a 'test' folder next to the source file.
    const sibling = path.join(dir, "..", "test", testFileName);
    if (
      workspaceRoot !== undefined &&
      !isInsideDirectory(workspaceRoot, sibling)
    ) {
      return path.join(dir, "test", testFileName);
    }
    return sibling;
  }

  /**
   * Detects package name from file content.
   */
  public detectPackage(text: string): string {
    const match = text.match(/^package\s+([\w.]+)/m);
    return match ? match[1] : "";
  }
}
