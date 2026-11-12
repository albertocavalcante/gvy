// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["client/src/**/*.ts"],
    rules: {
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "@typescript-eslint/no-explicit-any": "warn",
    },
    languageOptions: {
      parserOptions: {
        project: "./client/tsconfig.json",
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    // TODO(#1801): Extend after classifying the remaining adapters' effects.
    //   See: https://github.com/albertocavalcante/gvy/issues/1801
    files: ["client/src/features/ai/**/*.ts"],
    rules: {
      "@typescript-eslint/no-floating-promises": [
        "error",
        { checkThenables: true, ignoreVoid: false },
      ],
      "@typescript-eslint/no-misused-promises": [
        "error",
        { checksVoidReturn: true },
      ],
    },
  },
  {
    files: ["client/test/**/*.ts"],
    rules: {
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-unused-expressions": "off",
      "@typescript-eslint/no-require-imports": "warn",
    },
    languageOptions: {
      parserOptions: {
        project: false,
      },
    },
  },
  {
    ignores: [
      "node_modules/",
      "client/node_modules/",
      "client/out/",
      "server/",
      "tools/",
      "*.js",
      "*.mjs",
    ],
  },
);
