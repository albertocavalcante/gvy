// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { copyFile } from 'node:fs/promises';
await copyFile('svelte/build/not-found/index.html', 'svelte/build/404.html');
