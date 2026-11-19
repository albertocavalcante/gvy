@echo off
rem SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
rem SPDX-License-Identifier: Apache-2.0
where node >nul 2>&1
if errorlevel 1 exit /b 0
node "%~dp0source-stamp.mjs" status
exit /b %errorlevel%
