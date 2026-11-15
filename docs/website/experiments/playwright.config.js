// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', workers: 1, reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4321/gvy/', trace: 'retain-on-failure' },
  webServer: { command: `node serve.mjs ${process.env.CANDIDATE === 'svelte' ? 'svelte/build' : 'astro/dist'}`, url: 'http://127.0.0.1:4321/gvy/', reuseExistingServer: false }
});
