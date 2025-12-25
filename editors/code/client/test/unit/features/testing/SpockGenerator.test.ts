
import * as assert from 'assert';
import { SpockGenerator } from '../../../../src/features/testing/SpockGenerator';

describe('SpockGenerator', () => {
    const generator = new SpockGenerator();

    it('resolveTestPath: standard src/main/groovy layout', () => {
        const inputPath = '/project/src/main/groovy/com/example/UserService.groovy';
        const expectedPath = '/project/src/test/groovy/com/example/UserServiceSpec.groovy';

        const result = generator.resolveTestPath(inputPath);
        assert.strictEqual(result, expectedPath);
    });

    it('resolveTestPath: flat layout fallback', () => {
        const inputPath = '/project/lib/UserService.groovy';
        // Fallback expects sibling 'test' folder
        // Logic: dirname -> .. -> test -> filenameSpec
        const result = generator.resolveTestPath(inputPath);
        assert.ok(result?.endsWith('UserServiceSpec.groovy'));
        assert.ok(result?.includes('test'));
    });

    it('generateSpec: generates correct Spock structure', () => {
        const methods = [{ name: 'save' }, { name: 'delete' }];
        const content = generator.generateSpec('UserService', methods, 'com.example');

        assert.ok(content.includes('package com.example'));
        assert.ok(content.includes('class UserServiceSpec extends Specification'));
        assert.ok(content.includes('def "UserService should instantiate"'));
        assert.ok(content.includes('def "should test save"'));
        assert.ok(content.includes('def "should test delete"'));
    });
});

