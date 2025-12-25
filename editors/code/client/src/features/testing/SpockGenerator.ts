import * as path from 'path';

export interface MethodInfo {
    name: string;
}

export class SpockGenerator {

    /**
     * Generates the content for a Spock Specification file.
     */
    public generateSpec(className: string, methods: MethodInfo[], packageName: string): string {
        // TODO: Make suffix configurable (e.g. 'Spec', 'SpockTest', 'Test')
        const specName = `${className}Spec`;

        const methodTests = methods.map(m => {
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
        }).join('\n\n');

        return `package ${packageName}

import spock.lang.Specification

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
    public resolveTestPath(sourcePath: string): string | undefined {
        // Heuristic: Replace 'src/main' with 'src/test'
        if (sourcePath.includes(path.sep + 'main' + path.sep)) {
            const testPath = sourcePath.replace(path.sep + 'main' + path.sep, path.sep + 'test' + path.sep);

            // Handle suffix change: .groovy -> Spec.groovy
            // TODO: Use configurable suffix
            if (testPath.endsWith('.groovy')) {
                return testPath.replace('.groovy', 'Spec.groovy');
            }
        }

        // Fallback: If not in src/main structure, create in 'test' sibling folder
        // TODO: Handle non-standard layouts better
        const dir = path.dirname(sourcePath);
        const fileName = path.basename(sourcePath);
        const testFileName = fileName.replace('.groovy', 'Spec.groovy');
        return path.join(dir, '..', 'test', testFileName);
    }

    /**
     * Detects package name from file content.
     */
    public detectPackage(text: string): string {
        const match = text.match(/^package\s+([\w.]+)/m);
        return match ? match[1] : '';
    }
}
