#!/usr/bin/env node
// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// @ts-check

const { main, reportError } = require("./install-extension.js");

Promise.resolve()
  .then(() => main(["--from-ci", ...process.argv.slice(2)]))
  .catch(reportError);
